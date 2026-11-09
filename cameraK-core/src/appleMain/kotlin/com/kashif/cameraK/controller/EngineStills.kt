@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.engine.TraceNames
import com.kashif.cameraK.engine.Tracer
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.StorageFailure
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSUUID
import kotlin.coroutines.resume

private const val TAG = "CameraK"

/**
 * An iOS engine's stills (design §2.4): one session-queue block builds the settings from the photo
 * output as it is then and hands them to it; AVFoundation's callbacks stay on its own queue, and the
 * photo is written to app-owned storage off the session queue. The captures still waiting on
 * AVFoundation are kept under [lock], so close can complete them with Closed.
 */
internal class EngineStills(
    private val lock: EngineLock,
    private val generations: EngineGenerations,
    private val custom: CustomCameraController,
    private val config: CameraConfiguration,
    private val tracer: Tracer,
    private val dispatchers: CameraKDispatchers,
) {
    /** Captures waiting on AVFoundation, which close completes with Closed. Under [lock]. */
    private val pending = mutableSetOf<StillRequest>()
    private val heicRefusalLogged = atomic(false)

    /**
     * Takes one still. One `CameraK.capture` interval covers the call. Close completes a capture still
     * waiting with Closed; a cancelled capture's delegate lives on until AVFoundation's last callback,
     * and its photo goes nowhere.
     */
    suspend fun capture(): CameraKResult<CapturedImage> {
        generations.notOpen()?.let { return it }
        return tracer.beginInterval(TraceNames.CAPTURE).use { takeStill() }
    }

    /** Completes every capture still waiting on AVFoundation with Closed. */
    fun closeAll() {
        lock.locked { pending.toList().also { pending.clear() } }.forEach { it.settle(ENGINE_CLOSED) }
    }

    private suspend fun takeStill(): CameraKResult<CapturedImage> {
        val photo = suspendCancellableCoroutine { continuation ->
            val request = StillRequest(continuation)
            if (!lock.locked { !generations.closedLocked && pending.add(request) }) {
                continuation.resume(ENGINE_CLOSED)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { request.detach() }
            custom.afterQueuedSessionWork {
                val outcome = try {
                    stillRefusal()
                        ?: if (custom.capturePhotoOnQueue(request)) null else engineFailure(CameraKError.NotReady)
                } catch (e: Exception) {
                    CameraKLogger.e(TAG, "issuing a capture failed", e)
                    engineFailure(CameraKError.Internal(e))
                }
                outcome?.let(request::settle)
            }
        }
        val data = when (photo) {
            is CameraKResult.Failure -> return photo
            is CameraKResult.Success -> photo.value
        }
        return withContext(dispatchers.io) { saveStill(data) }
    }

    /** Session queue: why no capture can be issued now; null when one can. */
    private fun stillRefusal(): CameraKResult.Failure? {
        generations.unusable()?.let { return it }
        if (config.imageFormat != ImageFormat.HEIC || custom.canCaptureHeic) return null
        if (heicRefusalLogged.compareAndSet(expect = false, update = true)) {
            CameraKLogger.e(TAG, "this camera cannot encode HEIC (no HEVC photo codec); captures are refused")
        }
        return engineFailure(CameraKError.Unsupported(CameraFeature.IMAGE_FORMAT))
    }

    /** Writes [photo] where the configuration says, in its format and aspect ratio. Runs off the session queue. */
    private fun saveStill(photo: NSData): CameraKResult<CapturedImage> {
        val format = config.imageFormat
        val path = try {
            "${appOwnedDirectory(config.outputDirectory)}/IMG_${NSUUID().UUIDString}.${format.extension}"
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "creating the capture's directory failed", e)
            return engineFailure(CameraKError.Storage(StorageFailure.NOT_WRITABLE, cause = e))
        }
        val written = writeStill(photo, format, config.aspectRatio, path).getOrElse { e ->
            CameraKLogger.e(TAG, "writing the captured photo failed", e)
            removeFileIfPresent(path)
            val onDisk = e.cause is NSErrorException
            return engineFailure(
                if (onDisk) CameraKError.Storage(StorageFailure.NOT_WRITABLE, cause = e) else CameraKError.Internal(e),
            )
        }
        return CameraKResult.Success(CapturedImage(path, format, written.width, written.height))
    }

    /**
     * One capture's AVFoundation callbacks, which arrive here and only here. It settles once: with the
     * photo, the error that prevented it, or Closed; a detached (cancelled) capture's photo goes nowhere.
     */
    private inner class StillRequest(private val continuation: CancellableContinuation<CameraKResult<NSData>>) :
        PhotoCaptureCallbacks {
        private val settled = atomic(false)
        private val detached = atomic(false)

        fun detach() {
            detached.value = true
        }

        override val stillWanted: Boolean get() = !settled.value && !detached.value

        fun settle(result: CameraKResult<NSData>) {
            if (!settled.compareAndSet(expect = false, update = true)) return
            lock.locked { pending.remove(this) }
            if (detached.value) {
                CameraKLogger.d(TAG, "discarding the photo of a cancelled capture")
                return
            }
            continuation.resume(result)
        }

        override fun photoProcessed(data: NSData?, error: NSError?) = settle(
            when {
                error != null -> engineFailure(photoError(error))
                data == null -> engineFailure(
                    CameraKError.Internal(IllegalStateException("AVFoundation delivered no photo")),
                )
                else -> CameraKResult.Success(data)
            },
        )

        override fun captureFinished(error: NSError?) = settle(
            engineFailure(
                error?.let(::photoError)
                    ?: CameraKError.Internal(
                        IllegalStateException("AVFoundation finished the capture without a photo"),
                    ),
            ),
        )
    }

    private fun photoError(error: NSError): CameraKError {
        CameraKLogger.e(TAG, "the photo capture failed", NSErrorException(error))
        return cameraKErrorFor(error)
    }
}

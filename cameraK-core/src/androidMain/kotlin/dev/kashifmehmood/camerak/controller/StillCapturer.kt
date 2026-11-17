@file:kotlin.OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.controller

import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ExperimentalZeroShutterLag
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import dev.kashifmehmood.camerak.capture.CapturedImage
import dev.kashifmehmood.camerak.engine.TraceNames
import dev.kashifmehmood.camerak.engine.Tracer
import dev.kashifmehmood.camerak.enums.CameraLens
import dev.kashifmehmood.camerak.enums.FlashMode
import dev.kashifmehmood.camerak.enums.ImageFormat
import dev.kashifmehmood.camerak.enums.QualityPrioritization
import dev.kashifmehmood.camerak.error.CameraKError
import dev.kashifmehmood.camerak.error.StorageFailure
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import dev.kashifmehmood.camerak.utils.CameraKLogger
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.resume

private const val TAG = "CameraK"

/**
 * The ImageCapture use case and every still taken through it: one continuation per request, written
 * to a file in app-owned storage and nowhere else (Decision 10). CameraX's file output always writes JPEG; a PNG configuration gets a real
 * PNG, transcoded on [imageProcessingExecutor] ([PngTranscoder]). The holder counts captures in
 * flight; nothing here limits them.
 */
internal class StillCapturer(
    private val backend: CameraXBackend,
    private val files: OutputFileFactory,
    private val imageProcessingExecutor: ExecutorService,
    private val callbackExecutor: Executor,
    private val qualityPriority: QualityPrioritization,
    /** JPEG or PNG; the engine refuses every other format at start. */
    val format: ImageFormat,
    private val tracer: () -> Tracer,
    private val isReleased: () -> Boolean,
    private val metadata: () -> ImageCapture.Metadata,
    private val boundLens: () -> CameraLens?,
    /** Why a capture cannot go to CameraX now (the session's failure, or not open); null when it can. Main thread. */
    private val refusal: () -> CameraKError?,
    /** The capture session, exposure compensation index and autofocus state of each capture result; on the camera's thread. */
    private val onCaptureReported: (session: Any, index: Int, afState: Int?) -> Unit,
) {
    /** The ImageCapture use case of the current bind, or null before the first one. */
    var imageCapture: ImageCapture? = null
        private set

    // Captures whose CameraX callback has not arrived yet. Whoever removes a continuation (the
    // callback or failInFlight()) is the one that resumes it.
    private val inFlight: MutableSet<CancellableContinuation<CameraKResult<File>>> =
        Collections.newSetFromMap(ConcurrentHashMap())

    private val imageProcessing = imageProcessingExecutor.asCoroutineDispatcher()

    // The bind whose ImageCapture reports exposure: a result of an earlier bind's session can still arrive.
    @Volatile private var reportingBind = 0

    /**
     * Builds the image capture use case for the next bind. [QualityPrioritization.NONE] states no
     * preference and captures at maximum quality, as it always did on a device that was not short
     * of memory.
     */
    @OptIn(ExperimentalZeroShutterLag::class, ExperimentalCamera2Interop::class)
    fun configure(resolutionSelector: ResolutionSelector, flashMode: FlashMode, pinnedPhysicalId: String?) {
        val bind = ++reportingBind
        imageCapture = ImageCapture.Builder()
            .setFlashMode(flashMode.toCameraXFlashMode())
            .setCaptureMode(
                when (qualityPriority) {
                    QualityPrioritization.QUALITY -> ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                    QualityPrioritization.SPEED -> ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
                    QualityPrioritization.BALANCED -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                    QualityPrioritization.NONE -> ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                },
            )
            .setResolutionSelector(resolutionSelector)
            .apply { pinnedPhysicalId?.let { Camera2Interop.Extender(this).setPhysicalCameraId(it) } }
            .apply { backend.onCaptureReported(this, captureReporter(bind)) }
            .build()
    }

    // Passes a capture result on only while [bind] is the latest: a result of an earlier bind's session can still arrive.
    private fun captureReporter(bind: Int): (Any, Int, Int?) -> Unit = { session, index, afState ->
        if (bind == reportingBind) onCaptureReported(session, index, afState)
    }

    /**
     * Takes one still as [capture] does and reports its file with the upright size read from the
     * written file, so a 1:1 capture reports the square CameraX wrote. Main thread.
     */
    suspend fun captureImage(): CameraKResult<CapturedImage> = when (val saved = capture()) {
        is CameraKResult.Failure -> saved
        is CameraKResult.Success -> {
            val file = saved.value
            val (width, height) = withContext(imageProcessing) { uprightSize(file, jpeg = format != ImageFormat.PNG) }
            CameraKResult.Success(CapturedImage(file.absolutePath, format, width, height))
        }
    }

    /**
     * Takes one still into a new app-owned file and returns that file, a PNG for a PNG
     * configuration. Main thread. A caller that leaves before CameraX is asked sends nothing; one that
     * leaves later does not stop CameraX, which still writes the file, and that file is deleted.
     */
    suspend fun capture(): CameraKResult<File> {
        if (isReleased()) return CameraKResult.Failure(CameraKError.Closed)
        val capture = imageCapture ?: return CameraKResult.Failure(CameraKError.NotReady)
        val file = when (val claimed = files.photoFile("jpg")) {
            is CameraKResult.Failure -> return claimed
            is CameraKResult.Success -> claimed.value
        }
        val trace = tracer().beginInterval(TraceNames.CAPTURE)
        return try {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { inFlight.remove(continuation) }
                // A caller that had already left never reaches CameraX.
                if (!continuation.isActive) {
                    file.delete()
                    return@suspendCancellableCoroutine
                }
                // Making the folder left the main thread: the session can have failed meanwhile, and CameraX
                // would queue the request for a camera it does not reopen.
                refusal()?.let { error ->
                    file.delete()
                    continuation.resume(CameraKResult.Failure(error))
                    return@suspendCancellableCoroutine
                }
                inFlight += continuation
                val options = ImageCapture.OutputFileOptions.Builder(file).setMetadata(metadata()).build()
                backend.takePicture(capture, options, callbackExecutor, SavedCallback(continuation, file))
            }
        } finally {
            trace.close()
        }
    }

    private inner class SavedCallback(
        private val continuation: CancellableContinuation<CameraKResult<File>>,
        private val file: File,
    ) : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
            if (isReleased() || continuation !in inFlight) {
                // The caller was already told this capture failed; leave no unreported file.
                CameraKLogger.d(TAG, "Discarding a capture saved after it was failed")
                file.delete()
                return
            }
            if (format != ImageFormat.PNG) {
                resume(CameraKResult.Success(file))
                return
            }
            onImageProcessing {
                resume(
                    try {
                        transcoded(file)
                    } catch (e: Exception) {
                        CameraKLogger.e(TAG, "Writing the PNG capture failed", e)
                        file.delete()
                        CameraKResult.Failure(CameraKError.Storage(StorageFailure.NOT_WRITABLE, cause = e))
                    },
                )
            }
        }

        override fun onError(exc: ImageCaptureException) {
            CameraKLogger.e(TAG, "Image capture failed", exc)
            file.delete()
            resume(CameraKResult.Failure(captureError(exc)))
        }

        private fun transcoded(jpeg: File): CameraKResult<File> {
            val png = when (val claimed = files.siblingFile(jpeg, "png")) {
                is CameraKResult.Failure -> return claimed.also { jpeg.delete() }
                is CameraKResult.Success -> claimed.value
            }
            try {
                PngTranscoder.transcode(jpeg, png)
            } catch (e: Exception) {
                png.delete()
                throw e
            }
            return CameraKResult.Success(png)
        }

        // A capture failed or closed while its file was written: nobody is told of that file, so it goes.
        private fun resume(result: CameraKResult<File>) {
            if (inFlight.remove(continuation)) {
                continuation.resume(result)
            } else if (result is CameraKResult.Success) {
                result.value.delete()
            }
        }
    }

    private fun captureError(exc: ImageCaptureException): CameraKError = when (exc.imageCaptureError) {
        ImageCapture.ERROR_FILE_IO -> CameraKError.Storage(StorageFailure.NOT_WRITABLE, cause = exc)
        ImageCapture.ERROR_CAMERA_CLOSED -> CameraKError.NotReady
        ImageCapture.ERROR_CAPTURE_FAILED -> CameraKError.Hardware(exc.imageCaptureError, exc)
        ImageCapture.ERROR_INVALID_CAMERA -> CameraKError.NoCamera(boundLens(), exc)
        else -> CameraKError.Internal(exc)
    }

    /** Runs [block] on the image-processing executor, or here once that has shut down. */
    private fun onImageProcessing(block: () -> Unit) {
        try {
            imageProcessingExecutor.execute(block)
        } catch (e: RejectedExecutionException) {
            CameraKLogger.w(TAG, "Image processing has shut down; finishing the capture on the calling thread", e)
            block()
        }
    }

    /**
     * Fails every capture still waiting for a CameraX callback with [error]; a file CameraX writes for
     * one later is deleted.
     */
    fun failInFlight(error: CameraKError = CameraKError.Closed) {
        inFlight.toList().forEach { continuation ->
            if (inFlight.remove(continuation)) continuation.resume(CameraKResult.Failure(error))
        }
    }
}

/** The error every 1.x call made on, or still waiting in, a released controller reports. */
internal fun controllerReleased() = IllegalStateException("Camera controller was released")

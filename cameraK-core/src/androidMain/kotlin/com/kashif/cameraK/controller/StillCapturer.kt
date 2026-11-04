package com.kashif.cameraK.controller

import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ExperimentalZeroShutterLag
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import com.kashif.cameraK.engine.TraceNames
import com.kashif.cameraK.engine.Tracer
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.StorageFailure
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.resume

private const val TAG = "CameraK"

/**
 * The ImageCapture use case and every still taken through it: one continuation per request, written
 * to a file in app-owned storage and nowhere else (Decision 10), and the capture listeners each saved
 * image is delivered to. CameraX's file output always writes JPEG; a PNG configuration gets a real
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
) {
    /** The ImageCapture use case of the current bind, or null before the first one. */
    var imageCapture: ImageCapture? = null
        private set

    // Added and removed on any thread (plugins), iterated on imageProcessingExecutor: copy-on-write,
    // so every iteration is a consistent snapshot and concurrent registrations are never lost.
    private val listeners = CopyOnWriteArrayList<(ByteArray) -> Unit>()

    // Captures whose CameraX callback has not arrived yet. Whoever removes a continuation (the
    // callback or failInFlight()) is the one that resumes it.
    private val inFlight: MutableSet<CancellableContinuation<CameraKResult<File>>> =
        Collections.newSetFromMap(ConcurrentHashMap())

    /**
     * Builds the image capture use case for the next bind. [QualityPrioritization.NONE] states no
     * preference and captures at maximum quality, as it always did on a device that was not short
     * of memory.
     */
    @OptIn(ExperimentalZeroShutterLag::class, ExperimentalCamera2Interop::class)
    fun configure(resolutionSelector: ResolutionSelector, flashMode: FlashMode, pinnedPhysicalId: String?) {
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
            .build()
    }

    /**
     * Takes one still into a new app-owned file and returns that file, a PNG for a PNG
     * configuration. Main thread. A caller that leaves before CameraX is asked sends nothing; one that
     * leaves later does not stop CameraX, whose file still reaches the capture listeners.
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
            if (isReleased()) {
                // The release already told the caller this capture failed; leave no unreported file.
                CameraKLogger.d(TAG, "Discarding a capture saved after the camera was closed")
                file.delete()
                return
            }
            if (format != ImageFormat.PNG) {
                finish(CameraKResult.Success(file))
                return
            }
            onImageProcessing {
                finish(
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

        private fun finish(result: CameraKResult<File>) {
            if (result is CameraKResult.Success && listeners.isNotEmpty()) {
                onImageProcessing { deliverToListeners(result.value) }
            }
            resume(result)
        }

        private fun resume(result: CameraKResult<File>) {
            if (inFlight.remove(continuation)) continuation.resume(result)
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
     * Runs on [imageProcessingExecutor]. Nothing may escape: an exception here reaches the thread's
     * uncaught handler and kills the host process. Each listener is isolated from the others.
     */
    private fun deliverToListeners(file: File) {
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Reading the captured image for capture listeners failed", e)
            return
        } catch (e: OutOfMemoryError) {
            CameraKLogger.e(TAG, "Reading the captured image for capture listeners ran out of memory", e)
            return
        }
        for (listener in listeners) {
            try {
                listener(bytes)
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "Image capture listener failed", e)
            }
        }
    }

    /** Adds a listener for captured images. Safe to call from any thread. */
    fun addListener(listener: (ByteArray) -> Unit) {
        listeners.add(listener)
    }

    /** Removes a listener added with [addListener]. Safe to call from any thread. */
    fun removeListener(listener: (ByteArray) -> Unit) {
        listeners.remove(listener)
    }

    /** Drops every capture listener. */
    fun clearListeners() {
        listeners.clear()
    }

    /** Fails every capture still waiting for a CameraX callback, which is now ignored. */
    fun failInFlight() {
        inFlight.toList().forEach { continuation ->
            if (inFlight.remove(continuation)) continuation.resume(CameraKResult.Failure(CameraKError.Closed))
        }
    }
}

/** The error every 1.x call made on, or still waiting in, a released controller reports. */
internal fun controllerReleased() = IllegalStateException("Camera controller was released")

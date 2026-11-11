@file:kotlin.OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ExperimentalZeroShutterLag
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.engine.TraceNames
import com.kashif.cameraK.engine.Tracer
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.StorageFailure
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
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
    /** The capture session and exposure compensation index of each capture result; on the camera's thread. */
    private val onExposureReported: (session: Any, index: Int) -> Unit,
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
            .apply { backend.onExposureReported(this, exposureReporter(bind)) }
            .build()
    }

    // Passes a capture result on only while [bind] is the latest: a result of an earlier bind's session can still arrive.
    private fun exposureReporter(bind: Int): (Any, Int) -> Unit = { session, index ->
        if (bind == reportingBind) onExposureReported(session, index)
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
     * leaves later does not stop CameraX, which still writes the file.
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

    /** Fails every capture still waiting for a CameraX callback, which is now ignored. */
    fun failInFlight() {
        inFlight.toList().forEach { continuation ->
            if (inFlight.remove(continuation)) continuation.resume(CameraKResult.Failure(CameraKError.Closed))
        }
    }
}

/** The error every 1.x call made on, or still waiting in, a released controller reports. */
internal fun controllerReleased() = IllegalStateException("Camera controller was released")

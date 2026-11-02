package com.kashif.cameraK.controller

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ExperimentalZeroShutterLag
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.core.content.ContextCompat
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import kotlin.coroutines.resume

private const val TAG = "CameraK"
private const val MAX_CONCURRENT_CAPTURES = 3

/**
 * The ImageCapture use case and every still taken through it: one continuation per request, a soft
 * cap on captures in flight, and the capture listeners each saved image is delivered to.
 */
internal class StillCapturer(
    private val context: Context,
    private val backend: CameraXBackend,
    private val files: OutputFileFactory,
    private val imageProcessingExecutor: ExecutorService,
    private val qualityPriority: QualityPrioritization,
    private val isReleased: () -> Boolean,
    private val metadata: () -> ImageCapture.Metadata,
) {
    /** The ImageCapture use case of the current bind, or null before the first one. */
    var imageCapture: ImageCapture? = null
        private set

    // Added and removed on any thread (plugins), iterated on imageProcessingExecutor: copy-on-write,
    // so every iteration is a consistent snapshot and concurrent registrations are never lost.
    private val listeners = CopyOnWriteArrayList<(ByteArray) -> Unit>()

    // Captures whose CameraX callback has not arrived yet. Whoever removes a continuation (the
    // callback or failInFlight()) is the one that resumes it.
    private val inFlight: MutableSet<CancellableContinuation<ImageCaptureResult>> =
        Collections.newSetFromMap(ConcurrentHashMap())

    // One permit per capture in flight. Each capture gives its permit back exactly once, through
    // its CaptureSlot, whichever of success, error or cancellation settles it first.
    private val slots = Semaphore(MAX_CONCURRENT_CAPTURES)

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

    suspend fun takePictureToFile(): ImageCaptureResult = suspendCancellableCoroutine { cont ->
        if (isReleased()) {
            cont.resume(ImageCaptureResult.Error(controllerReleased()))
            return@suspendCancellableCoroutine
        }
        if (!slots.tryAcquire()) {
            CameraKLogger.w(TAG, "Burst queue full, dropping frame")
            cont.resume(ImageCaptureResult.Error(Exception("Burst queue full, capture rejected")))
            return@suspendCancellableCoroutine
        }
        val slot = CaptureSlot()
        // Registered before the capture starts, so a cancellation at any point releases the slot.
        cont.invokeOnCancellation {
            inFlight.remove(cont)
            slot.release()
        }
        // A caller that had already left was cancelled on entry, and the handler above released its slot.
        if (!cont.isActive) return@suspendCancellableCoroutine

        performCaptureToFile(cont, slot)
    }

    /** A capture's hold on one of [slots]; the first [release] gives it back, later ones do nothing. */
    private inner class CaptureSlot {
        private val settled = atomic(false)

        fun release() {
            if (settled.compareAndSet(false, true)) slots.release()
        }
    }

    /**
     * Perform fast file-based capture without ByteArray processing.
     * Directly saves to final destination and returns file path.
     */
    private fun performCaptureToFile(continuation: CancellableContinuation<ImageCaptureResult>, slot: CaptureSlot) {
        val capture = imageCapture ?: run {
            slot.release()
            continuation.resume(ImageCaptureResult.Error(Exception("ImageCapture use case is not initialized.")))
            return
        }
        // A destination that cannot be created fails this capture instead of escaping takePictureToFile.
        val destination = try {
            files.photoDestination()
        } catch (e: java.io.IOException) {
            CameraKLogger.e(TAG, "Creating the capture file failed", e)
            slot.release()
            continuation.resume(ImageCaptureResult.Error(e))
            return
        }
        val outputFile = destination.file
        val galleryValues = destination.galleryValues
        val options = if (galleryValues != null) {
            ImageCapture.OutputFileOptions.Builder(
                context.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                galleryValues,
            )
        } else {
            ImageCapture.OutputFileOptions.Builder(checkNotNull(outputFile))
        }.setMetadata(metadata()).build()

        inFlight.add(continuation)
        backend.takePicture(
            capture,
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    slot.release()
                    val galleryUri = if (outputFile == null) output.savedUri else null
                    if (isReleased()) {
                        // The release already told the caller this capture failed; don't leave an
                        // unreported file or gallery entry behind or post to the shut-down executor.
                        CameraKLogger.d(TAG, "Discarding a capture saved after the controller was released")
                        outputFile?.delete()
                        galleryUri?.let(files::deleteGalleryEntry)
                        return
                    }

                    val result = when {
                        outputFile != null -> ImageCaptureResult.SuccessWithFile(outputFile.absolutePath)
                        galleryUri != null -> ImageCaptureResult.SuccessWithUri(galleryUri.toString())
                        else -> {
                            CameraKLogger.e(TAG, "CameraX saved a gallery capture without reporting its entry")
                            ImageCaptureResult.Error(IllegalStateException("The saved capture has no gallery entry"))
                        }
                    }

                    // Notify capture listeners (e.g. ImageSaverPlugin auto-save) off the main
                    // thread, reading the just-saved image only when someone is listening.
                    if (result !is ImageCaptureResult.Error && listeners.isNotEmpty()) {
                        imageProcessingExecutor.execute { deliverToListeners(outputFile, galleryUri) }
                    }

                    if (inFlight.remove(continuation)) {
                        continuation.resume(result)
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    CameraKLogger.e(TAG, "Image capture failed", exc)
                    slot.release()
                    outputFile?.delete() // Clean up failed capture file
                    val reportError = {
                        if (inFlight.remove(continuation)) {
                            continuation.resume(ImageCaptureResult.Error(exc))
                        }
                    }
                    if (galleryValues == null) {
                        reportError()
                        return
                    }
                    runOffMain {
                        files.deleteFailedGalleryCapture(galleryValues)
                        reportError()
                    }
                }
            },
        )
    }

    /** Runs MediaStore and file work off the main thread, or here once the controller has shut down. */
    private fun runOffMain(block: () -> Unit) {
        try {
            imageProcessingExecutor.execute(block)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            CameraKLogger.w(TAG, "Image processing has shut down; cleaning up the capture on the calling thread", e)
            block()
        }
    }

    /**
     * Runs on [imageProcessingExecutor]. Nothing may escape: an exception here reaches the thread's
     * uncaught handler and kills the host process. Each listener is isolated from the others.
     */
    private fun deliverToListeners(file: File?, galleryUri: Uri?) {
        val bytes = try {
            file?.readBytes()
                ?: galleryUri?.let { uri ->
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw java.io.IOException("The gallery capture could not be opened")
                }
                ?: return
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
            if (inFlight.remove(continuation)) {
                continuation.resume(ImageCaptureResult.Error(controllerReleased()))
            }
        }
    }
}

/** The error every call made on, or still waiting in, a released controller reports. */
internal fun controllerReleased() = IllegalStateException("Camera controller was released")

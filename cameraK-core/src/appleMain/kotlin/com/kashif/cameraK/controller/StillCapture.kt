package com.kashif.cameraK.controller

import com.kashif.cameraK.controller.CustomCameraController.CameraException
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.*
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSLock
import kotlin.concurrent.Volatile

private const val TAG = "CameraK"

/** How long a capture that failed with -11803 on a running session waits before it is issued again. */
internal const val NOT_RUNNING_RETRY_DELAY_MS = 500L

/**
 * A controller's photo output and still captures: the output setup adds, the settings each capture
 * and each prepare gets, the flash mode captures request, and the delegates kept alive until
 * AVFoundation's last callback. Session-queue work runs on [queue]; the session, the camera in use and
 * its facing come from the controller's other collaborators.
 */
internal class StillCapture(
    private val qualityPrioritization: QualityPrioritization,
    private val mirrorFrontCamera: Boolean,
    private val photoCapturer: PhotoCapturer,
    private val queue: SessionQueue,
    private val session: () -> CaptureSessionOps?,
    private val currentCamera: () -> CaptureDeviceOps?,
    private val isUsingFrontCamera: () -> Boolean,
    currentVideoOrientation: () -> AVCaptureVideoOrientation,
    private val reportError: (CameraException) -> Unit,
    /** The configured target resolution, the size QUALITY captures aim for; null for the camera's largest. */
    private val targetResolution: Pair<Int, Int>? = null,
    /** The session preset setup chose for stills. */
    private val stillPreset: () -> String? = { null },
) {
    @Volatile
    private var photoOutput: AVCapturePhotoOutput? = null

    /**
     * The orientation photos are captured in; AppleCameraEngine answers with its target orientation
     * when one is set, otherwise the device's.
     */
    @Volatile
    var captureOrientation: () -> AVCaptureVideoOrientation = currentVideoOrientation

    /**
     * How the photo connection is turned for a capture: the engine answers with the coordinator's angle
     * from iOS 17 when no target orientation is set, otherwise with [captureOrientation].
     */
    @Volatile
    var captureRotation: () -> VideoRotation = { VideoRotation.Orientation(captureOrientation()) }

    /** The format captures are taken in: HEIC asks the photo output for HEVC where it can encode it. */
    @Volatile
    var photoFormat: ImageFormat = ImageFormat.JPEG

    /** Whether outputs get their deferred start turned off (iOS 26 and later); the engine sets it. */
    @Volatile
    var turnsDeferredStartOff: Boolean = false

    /** The flash mode captures request; a mode the photo output lacks is turned off at capture time. */
    @Volatile
    var flashMode: AVCaptureFlashMode = AVCaptureFlashModeAuto

    /** The flash mode a capture's settings carry now: [flashMode], or off when the photo output lacks it. */
    val captureFlashMode: AVCaptureFlashMode get() = supportedFlashMode(flashMode)

    /** Whether captures request high resolution; QUALITY is the only prioritization that does. */
    private val highResolution: Boolean
        get() = qualityPrioritization == QualityPrioritization.QUALITY

    /** The size QUALITY captures ask for ([applyMaxPhotoDimensions]); null keeps the preset's. */
    @Volatile
    private var maxPhotoDimensions: PhotoDimensions? = null

    /** The AVFoundation prioritization for [qualityPrioritization]; null for NONE, which leaves the default. */
    private val avQualityPrioritization: AVCapturePhotoQualityPrioritization?
        get() = when (qualityPrioritization) {
            QualityPrioritization.QUALITY -> AVCapturePhotoQualityPrioritizationQuality
            QualityPrioritization.BALANCED -> AVCapturePhotoQualityPrioritizationBalanced
            QualityPrioritization.SPEED -> AVCapturePhotoQualityPrioritizationSpeed
            QualityPrioritization.NONE -> null
        }

    /** The flash modes the photo output supports with the camera now in use; none before setup. */
    val captureFlashModes: List<AVCaptureFlashMode>
        get() = photoOutput?.let(photoCapturer::supportedFlashModes).orEmpty()

    /** The codecs the photo output can encode a photo in with the camera now in use; none before setup. */
    val availablePhotoCodecs: List<String>
        get() = photoOutput?.let(photoCapturer::availablePhotoCodecTypes).orEmpty()

    /** Whether a HEIC capture can be taken: the photo output can encode HEVC with the camera now in use. */
    val canCaptureHeic: Boolean get() = AVVideoCodecTypeHEVC in availablePhotoCodecs

    /** The codec a capture asks for: HEVC for [photoFormat] HEIC where the output has it, JPEG otherwise. */
    private val photoCodec: String
        get() = if (photoFormat == ImageFormat.HEIC && canCaptureHeic) AVVideoCodecTypeHEVC!! else AVVideoCodecJPEG!!

    /** [mode] when the photo output supports it, otherwise off (devices without a flash, such as iPads). */
    private fun supportedFlashMode(mode: AVCaptureFlashMode): AVCaptureFlashMode =
        if (mode in captureFlashModes) mode else AVCaptureFlashModeOff

    /** Adds a new photo output to [ops], inside the setup transaction; throws when the session refuses it. */
    fun setUpOutput(ops: CaptureSessionOps) {
        photoOutput = AVCapturePhotoOutput()
        avQualityPrioritization?.let { photoOutput?.setMaxPhotoQualityPrioritization(it) }
        if (turnsDeferredStartOff) photoOutput?.let(photoCapturer::disableDeferredStart)

        if (ops.canAddOutput(photoOutput!!)) {
            ops.addOutput(photoOutput!!)
        } else {
            throw CameraException.ConfigurationError("Cannot add photo output")
        }
    }

    /** Forgets the photo output of a setup that failed. Session queue only. */
    fun discardOutput() {
        photoOutput = null
    }

    /** Forgets the photo output and its size with the released session. Session queue only. */
    fun releaseOnQueue() {
        photoOutput = null
        maxPhotoDimensions = null
    }

    /** Turns off [output]'s deferred start when outputs get it turned off (iOS 26 and later). */
    fun disableDeferredStartIfAsked(output: AVCaptureOutput) {
        if (turnsDeferredStartOff) photoCapturer.disableDeferredStart(output)
    }

    /**
     * Opens the photo output up to the size QUALITY asks for: the current camera's largest photo, or,
     * with a target resolution, the target. A target the still preset already reaches leaves the
     * output at the preset's size; one above it asks for the smallest photo that reaches it, else the
     * largest. The size must be one the camera's active format supports, so this runs on the session
     * queue once the camera (and its format) is settled: after setup and after each swap.
     */
    fun applyMaxPhotoDimensions() {
        if (!highResolution) return
        val output = photoOutput ?: return
        val sizes = currentCamera()?.supportedMaxPhotoDimensions.orEmpty()
        val target = targetResolution
        val wanted = when {
            target == null -> sizes.maxByOrNull { it.pixels }
            presetReaches(stillPreset(), target) -> null
            else -> sizes.filter { it.reaches(target) }.minByOrNull { it.pixels } ?: sizes.maxByOrNull { it.pixels }
        }
        maxPhotoDimensions = wanted
        if (wanted != null) photoCapturer.setMaxPhotoDimensions(output, wanted)
    }

    /**
     * Sets the flash mode later captures request. It is kept even before the photo output exists;
     * a capture turns off a mode the device lacks (devices without a flash, such as iPads).
     */
    fun setFlashMode(mode: AVCaptureFlashMode) {
        val before = supportedFlashMode(flashMode)
        flashMode = mode
        if (photoOutput != null && supportedFlashMode(mode) != mode) {
            CameraKLogger.w(TAG, "flash mode $mode is not supported on this device; captures use OFF")
        }
        if (supportedFlashMode(mode) != before) queue.async { prepareOnQueue() }
    }

    /**
     * New settings for one capture: codec, size, quality prioritization and flash. AVFoundation
     * refuses a settings object it has seen before, so every capture (and every prepare) gets its own.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun photoSettings(): AVCapturePhotoSettings {
        val settings = AVCapturePhotoSettings.photoSettingsWithFormat(
            mapOf(
                AVVideoCodecKey to photoCodec,
            ),
        )

        // iOS 16, CameraK's minimum, sizes photos with maxPhotoDimensions; the high-resolution flags are gone.
        maxPhotoDimensions?.let { settings.maxPhotoDimensions = it.toCMVideoDimensions() }
        avQualityPrioritization?.let { settings.photoQualityPrioritization = it }
        settings.flashMode = supportedFlashMode(flashMode)

        // Don't touch autoStillImageStabilizationEnabled: it's deprecated since iOS 13 and
        // setting it together with photoQualityPrioritization (set above) raises -17281
        // (invalid state). Stabilization is handled automatically by the prioritization level. (#113)
        return settings
    }

    /**
     * Issues one capture whose photo and completion go only to [callbacks], through a delegate of its
     * own that is kept alive until AVFoundation's last callback for it. Session queue only: the
     * settings are built from the photo output as it is now, after every piece of session work queued
     * before (a lens switch, a flash change), and handed to it in the same block (design §2.4). Returns
     * false, without calling [callbacks], when the camera is not ready.
     *
     * A capture that fails with -11803 (session not running) is issued once more after
     * [NOT_RUNNING_RETRY_DELAY_MS], while the same photo output and camera are in use and the session runs:
     * on an iPhone 14 Pro and 14 Pro Max, startRunning returned in a few milliseconds in about two
     * sessions in three, the first capture of such a session failed with -11803 while the session
     * reported running, and a capture issued half a second later always succeeded.
     */
    fun captureOnQueue(callbacks: PhotoCaptureCallbacks): Boolean {
        val output = photoOutput
        if (output == null || session()?.isRunning != true) return false
        issue(output, callbacks, mayRetry = true)
        return true
    }

    private fun issue(output: AVCapturePhotoOutput, callbacks: PhotoCaptureCallbacks, mayRetry: Boolean) {
        // Do NOT reconfigure the session preset here. Switching the preset synchronously right
        // before capturePhotoWithSettings disrupts auto-exposure, so the still is captured
        // mid-reconfiguration and comes out underexposed (#138). The preset is chosen once at
        // setup; memory pressure is handled by clearing buffer pools, not by downshifting capture.

        val settings = photoSettings()

        // The orientation the photo's metadata records, and the front-camera photo mirrored to match
        // the mirrored preview when configured (#112).
        val rotation = captureRotation()
        val mirrored = mirrorFrontCamera && isUsingFrontCamera()
        val camera = currentCamera()

        lateinit var delegate: PhotoCaptureDelegate
        delegate = PhotoCaptureDelegate(
            object : PhotoCaptureCallbacks {
                // AVFoundation calls one capture's delegate methods one after another.
                @Volatile
                private var notRunning: NSError? = null

                @Volatile
                private var photoForwarded = false

                override fun photoProcessed(data: NSData?, error: NSError?) {
                    if (mayRetry && error?.isAVFoundation(AVErrorSessionNotRunning) == true) {
                        notRunning = error
                        return
                    }
                    photoForwarded = true
                    callbacks.photoProcessed(data, error)
                }

                override fun captureFinished(error: NSError?) {
                    releaseDelegate(delegate)
                    val failure = notRunning ?: error?.takeIf {
                        mayRetry && !photoForwarded && it.isAVFoundation(AVErrorSessionNotRunning)
                    }
                    if (failure == null) {
                        callbacks.captureFinished(error)
                    } else {
                        queue.after(NOT_RUNNING_RETRY_DELAY_MS) { retryOnQueue(output, camera, callbacks, failure) }
                    }
                }
            },
        )
        retainDelegate(delegate)
        photoCapturer.capture(output, settings, rotation, mirrored, delegate)
    }

    // Session queue: the capture that failed with [failure] is issued again on [output] with [camera],
    // or, when the output was released, the camera switched or the session stopped meanwhile, ends
    // with that failure.
    private fun retryOnQueue(
        output: AVCapturePhotoOutput,
        camera: CaptureDeviceOps?,
        callbacks: PhotoCaptureCallbacks,
        failure: NSError,
    ) {
        if (photoOutput !== output || currentCamera() !== camera || session()?.isRunning != true) {
            callbacks.photoProcessed(null, failure)
            callbacks.captureFinished(failure)
            return
        }
        CameraKLogger.w(TAG, "a capture failed with -11803 while the session was running; issuing it again")
        try {
            issue(output, callbacks, mayRetry = false)
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "issuing a capture again failed", e)
            callbacks.photoProcessed(null, failure)
            callbacks.captureFinished(failure)
        }
    }

    /**
     * Has the photo output allocate its capture buffers now for the settings captures use, rather
     * than on the first shutter press. Runs on the session queue while the session runs: after it
     * starts, after a camera swap, and when the flash mode captures use changes.
     */
    fun prepareOnQueue() {
        val output = photoOutput ?: return
        if (session()?.isRunning != true) return
        photoCapturer.prepare(output, listOf(photoSettings())) { _, error ->
            if (error != null) {
                val failure = CameraException.ConfigurationError(
                    "Preparing the photo output failed: ${error.summary}",
                    NSErrorException(error),
                )
                CameraKLogger.e(TAG, "preparing the photo output failed", failure)
                reportError(failure)
            }
        }
    }

    // AVCam keeps each capture's delegate alive until AVFoundation's last callback for it.
    private val delegatesLock = NSLock()
    private val delegates = mutableSetOf<PhotoCaptureDelegate>()

    /**
     * Capture delegates retained until AVFoundation's last callback for their capture: AVFoundation
     * does not retain a photo delegate, so a cancelled capture's delegate must outlive its caller.
     */
    val outstandingDelegates: Int
        get() {
            delegatesLock.lock()
            try {
                return delegates.size
            } finally {
                delegatesLock.unlock()
            }
        }

    private fun retainDelegate(delegate: PhotoCaptureDelegate) {
        delegatesLock.lock()
        try {
            delegates += delegate
        } finally {
            delegatesLock.unlock()
        }
    }

    private fun releaseDelegate(delegate: PhotoCaptureDelegate) {
        delegatesLock.lock()
        try {
            delegates -= delegate
        } finally {
            delegatesLock.unlock()
        }
    }
}

/** Whether this photo size reaches [target] (its long and short sides), whichever way either is held. */
private fun PhotoDimensions.reaches(target: Pair<Int, Int>): Boolean =
    maxOf(width, height) >= maxOf(target.first, target.second) &&
        minOf(width, height) >= minOf(target.first, target.second)

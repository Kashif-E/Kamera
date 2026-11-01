package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toCPointer
import platform.AVFoundation.*
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIView
import platform.darwin.DISPATCH_QUEUE_PRIORITY_HIGH
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_get_specific
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_set_specific
import platform.darwin.dispatch_sync
import kotlin.collections.emptyList
import kotlin.concurrent.Volatile

private const val TAG = "CameraK"

/** Queue-specific key every controller tags its session queue with; the value is unique per controller. */
@OptIn(ExperimentalForeignApi::class)
private val sessionQueueKey = nativeHeap.alloc<ByteVar>().ptr
private val sessionQueueIds = atomic(0L)

/**
 * Convert CameraDeviceType enum to AVFoundation device type string
 */
private fun CameraDeviceType.toAVCaptureDeviceType(): String? = when (this) {
    CameraDeviceType.WIDE_ANGLE -> AVCaptureDeviceTypeBuiltInWideAngleCamera
    CameraDeviceType.TELEPHOTO -> AVCaptureDeviceTypeBuiltInTelephotoCamera
    CameraDeviceType.ULTRA_WIDE -> AVCaptureDeviceTypeBuiltInUltraWideCamera
    CameraDeviceType.MACRO -> null // Macro camera would need iOS 15+ check
    CameraDeviceType.DEFAULT -> AVCaptureDeviceTypeBuiltInWideAngleCamera
}

class CustomCameraController(
    val qualityPrioritization: QualityPrioritization,
    private var initialCameraLens: CameraLens = CameraLens.BACK,
    private val aspectRatio: AspectRatio = AspectRatio.RATIO_4_3,
    private val targetResolution: Pair<Int, Int>? = null,
    private val mirrorFrontCamera: Boolean = false,
) : NSObject(),
    AVCapturePhotoCaptureDelegateProtocol {
    private var sessionFactory: () -> CaptureSessionOps = { AVCaptureSessionOps() }
    private var inputConfigurator: ((CaptureSessionOps, CameraDeviceType) -> Boolean)? = null
    private var lensSwitcher: ((CaptureSessionOps, Boolean) -> Unit)? = null
    private var previewMirroring: (AVCaptureVideoPreviewLayer?, Boolean) -> Unit = ::mirrorPreview

    /**
     * Test seam: [sessionFactory] supplies the session, and [inputConfigurator], when set, adds the
     * initial video input in place of AVCaptureDevice discovery (the simulator has no camera).
     * [lensSwitcher], when set, adds the input for the lens switchCamera moves to (true for front)
     * in place of the device lookup, and [previewMirroring], when set, applies the preview layer's
     * mirroring in place of setting it on the layer's connection.
     */
    internal constructor(
        qualityPrioritization: QualityPrioritization,
        initialCameraLens: CameraLens = CameraLens.BACK,
        aspectRatio: AspectRatio = AspectRatio.RATIO_4_3,
        targetResolution: Pair<Int, Int>? = null,
        mirrorFrontCamera: Boolean = false,
        sessionFactory: () -> CaptureSessionOps,
        inputConfigurator: ((CaptureSessionOps, CameraDeviceType) -> Boolean)? = null,
        lensSwitcher: ((CaptureSessionOps, Boolean) -> Unit)? = null,
        previewMirroring: ((AVCaptureVideoPreviewLayer?, Boolean) -> Unit)? = null,
    ) : this(qualityPrioritization, initialCameraLens, aspectRatio, targetResolution, mirrorFrontCamera) {
        this.sessionFactory = sessionFactory
        this.inputConfigurator = inputConfigurator
        this.lensSwitcher = lensSwitcher
        previewMirroring?.let { this.previewMirroring = it }
    }

    /**
     * Every AVCaptureSession call (begin/commit, inputs, outputs, preset, start, stop) runs on this
     * serial queue, Apple's AVCam pattern: the session is not thread-safe, and startRunning between
     * begin and commitConfiguration raises NSGenericException, which aborts a Kotlin/Native process.
     * Only preview-layer work and callbacks hop to main.
     */
    @OptIn(ExperimentalForeignApi::class)
    private val sessionQueue = dispatch_queue_create("com.kashif.cameraK.session", null)

    @OptIn(ExperimentalForeignApi::class)
    private val sessionQueueContext = sessionQueueIds.incrementAndGet().toCPointer<CPointed>()

    init {
        @OptIn(ExperimentalForeignApi::class)
        dispatch_queue_set_specific(sessionQueue, sessionQueueKey, sessionQueueContext, null)
    }

    @OptIn(ExperimentalForeignApi::class)
    private val isOnSessionQueue: Boolean
        get() = dispatch_get_specific(sessionQueueKey) == sessionQueueContext

    /** Bumped by [cleanupSession] so a setup requested before it never reports ready. */
    private val setupGeneration = atomic(0)

    /** Written only on [sessionQueue]; read elsewhere only to check presence. */
    @Volatile
    private var session: CaptureSessionOps? = null

    var captureSession: AVCaptureSession?
        get() = session?.avSession

        @Deprecated(
            "No replacement: setupSession() creates and owns the session on the controller's session queue. " +
                "Assigning one from outside blocks the caller until that queue is free.",
        )
        set(value) {
            val replace = { session = value?.let(::AVCaptureSessionOps) }
            // Synchronous so the next read returns the new session; already on the queue, waiting on
            // it would deadlock.
            if (isOnSessionQueue) replace() else dispatch_sync(sessionQueue, replace)
        }
    private var backCamera: AVCaptureDevice? = null
    private var frontCamera: AVCaptureDevice? = null

    @Volatile
    private var currentCamera: AVCaptureDevice? = null

    @Volatile
    private var photoOutput: AVCapturePhotoOutput? = null

    /** A UIKit layer: read and written on main only. */
    var cameraPreviewLayer: AVCaptureVideoPreviewLayer? = null

    @Volatile
    private var isUsingFrontCamera = false

    var onPhotoCapture: ((NSData?) -> Unit)? = null
    var onError: ((CameraException) -> Unit)? = null
    var onSessionReady: (() -> Unit)? = null

    var flashMode: AVCaptureFlashMode = AVCaptureFlashModeAuto
    var torchMode: AVCaptureTorchMode = AVCaptureTorchModeAuto

    // Configuration changes for plugin outputs (Apple WWDC pattern). Touched only on sessionQueue.
    private val pendingConfigurations = mutableListOf<() -> Unit>()

    sealed class CameraException : Exception() {
        class DeviceNotAvailable : CameraException()
        class ConfigurationError(message: String) : CameraException()
        class CaptureError(message: String) : CameraException()
    }

    /**
     * Sets up the camera session with a specific device type.
     *
     * This allows selecting a particular camera (e.g. wide-angle, telephoto, or macro) at runtime,
     * which is especially useful on iPhones with multiple rear cameras (iPhone 13 and newer).
     *
     * If cameraDeviceType is null or unavailable, falls back to any available camera device.
     *
     * Example device types:
     * - AVCaptureDeviceTypeBuiltInWideAngleCamera
     * - AVCaptureDeviceTypeBuiltInTelephotoCamera
     * - AVCaptureDeviceTypeBuiltInUltraWideCamera
     * - AVCaptureDeviceTypeBuiltInMacroCamera
     */
    fun setupSession(cameraDeviceType: CameraDeviceType = CameraDeviceType.DEFAULT) {
        val generation = setupGeneration.value
        // Off the main thread to avoid UI stalls (#73), and on the session queue so nothing else
        // touches the session while it is being built.
        dispatch_async(sessionQueue) {
            if (setupGeneration.value != generation) return@dispatch_async

            val ops = sessionFactory()
            // Published before configuring so changes applied below (safeAddOutput) reach it.
            session = ops

            // One transaction: input, photo output, the final preset and every change queued so far.
            ops.beginConfiguration()
            val failure: CameraException? = try {
                val inputsReady = inputConfigurator?.invoke(ops, cameraDeviceType) ?: setupInputs(cameraDeviceType)
                if (inputsReady) {
                    setupPhotoOutput()
                    ops.setSessionPreset(targetResolution?.toPreset() ?: aspectRatio.toSessionPreset())
                    runPendingConfigurations()
                    null
                } else {
                    CameraException.DeviceNotAvailable()
                }
            } catch (e: CameraException) {
                CameraKLogger.e(TAG, "CameraK: session setup failed", e)
                e
            } finally {
                ops.commitConfiguration()
            }

            if (failure != null) {
                session = null
                photoOutput = null
                reportError(failure)
                return@dispatch_async
            }

            dispatch_async(dispatch_get_main_queue()) {
                if (setupGeneration.value == generation && session === ops) {
                    onSessionReady?.invoke()
                }
            }
        }
    }

    private fun reportError(error: CameraException) {
        dispatch_async(dispatch_get_main_queue()) {
            onError?.invoke(error)
        }
    }

    private fun Pair<Int, Int>.toPreset(): String? {
        val (w, h) = this
        return when {
            w >= 3840 && h >= 2160 -> AVCaptureSessionPreset3840x2160
            w >= 1920 && h >= 1080 -> AVCaptureSessionPreset1920x1080
            w >= 1280 && h >= 720 -> AVCaptureSessionPreset1280x720
            else -> null
        }
    }

    /** Whether captures request high resolution; QUALITY is the only prioritization that does. */
    private val highResolution: Boolean
        get() = qualityPrioritization == QualityPrioritization.QUALITY

    /** The AVFoundation prioritization for [qualityPrioritization]; null for NONE, which leaves the default. */
    private val avQualityPrioritization: AVCapturePhotoQualityPrioritization?
        get() = when (qualityPrioritization) {
            QualityPrioritization.QUALITY -> AVCapturePhotoQualityPrioritizationQuality
            QualityPrioritization.BALANCED -> AVCapturePhotoQualityPrioritizationBalanced
            QualityPrioritization.SPEED -> AVCapturePhotoQualityPrioritizationSpeed
            QualityPrioritization.NONE -> null
        }

    /** [mode] when the photo output supports it, otherwise off (devices without a flash, such as iPads). */
    private fun supportedFlashMode(mode: AVCaptureFlashMode): AVCaptureFlashMode {
        val supportedFlashModes = photoOutput?.supportedFlashModes() as? List<*>
        return if (supportedFlashModes?.contains(mode) == true) mode else AVCaptureFlashModeOff
    }

    private fun setupPhotoOutput() {
        photoOutput = AVCapturePhotoOutput()
        photoOutput?.setHighResolutionCaptureEnabled(highResolution)
        avQualityPrioritization?.let { photoOutput?.setMaxPhotoQualityPrioritization(it) }

        photoOutput?.setPreparedPhotoSettingsArray(emptyList<String>(), completionHandler = { settings, error ->
            if (error != null) {
                onError?.invoke(CameraException.ConfigurationError(error.localizedDescription))
            }
        })

        if (session?.canAddOutput(photoOutput!!) == true) {
            session?.addOutput(photoOutput!!)
        } else {
            throw CameraException.ConfigurationError("Cannot add photo output")
        }
    }

    /**
     * Single discovery entry point — setupInputs, switchToDeviceType and
     * getCameraCapabilities all enumerate through here.
     */
    private fun discoverDevices(deviceTypes: List<String>, position: AVCaptureDevicePosition): List<AVCaptureDevice> =
        AVCaptureDeviceDiscoverySession.discoverySessionWithDeviceTypes(
            deviceTypes,
            AVMediaTypeVideo,
            position,
        ).devices.mapNotNull { it as? AVCaptureDevice }

    private fun allLensDeviceTypes(): List<String> = listOfNotNull(
        AVCaptureDeviceTypeBuiltInWideAngleCamera,
        AVCaptureDeviceTypeBuiltInTelephotoCamera,
        AVCaptureDeviceTypeBuiltInUltraWideCamera,
    )

    /**
     * Enumerates every built-in lens (wide, telephoto, ultra-wide) on both positions.
     */
    fun getCameraCapabilities(): List<LensInfo> =
        discoverDevices(allLensDeviceTypes(), AVCaptureDevicePositionUnspecified).map { device ->
            LensInfo(
                id = device.uniqueID,
                deviceType = when (device.deviceType) {
                    AVCaptureDeviceTypeBuiltInUltraWideCamera -> CameraDeviceType.ULTRA_WIDE
                    AVCaptureDeviceTypeBuiltInTelephotoCamera -> CameraDeviceType.TELEPHOTO
                    AVCaptureDeviceTypeBuiltInWideAngleCamera -> CameraDeviceType.WIDE_ANGLE
                    else -> CameraDeviceType.DEFAULT
                },
                lens = if (device.position == AVCaptureDevicePositionFront) CameraLens.FRONT else CameraLens.BACK,
                minZoom = device.minAvailableVideoZoomFactor.toFloat(),
                maxZoom = device.maxAvailableVideoZoomFactor.toFloat(),
                hasFlash = device.hasFlash,
                isLogical = false,
            )
        }

    @OptIn(ExperimentalForeignApi::class)
    private fun setupInputs(cameraDeviceType: CameraDeviceType): Boolean {
        val deviceTypeString = cameraDeviceType.toAVCaptureDeviceType()

        val discovered = discoverDevices(
            deviceTypeString?.let { listOf(it) } ?: allLensDeviceTypes(),
            AVCaptureDevicePositionUnspecified,
        )
        val devices = discovered.ifEmpty {
            listOfNotNull(AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) as? AVCaptureDevice)
        }

        devices.forEach { cam ->
            when (cam.position) {
                AVCaptureDevicePositionBack -> backCamera = cam
                AVCaptureDevicePositionFront -> frontCamera = cam
            }
        }

        fun findByTypeAndPosition(type: String?, position: Long?): AVCaptureDevice? = devices.firstOrNull { cam ->
            (type == null || cam.deviceType == type) && (position == null || cam.position == position)
        }

        val requestedType = cameraDeviceType.toAVCaptureDeviceType()
        val desiredPosition = when (initialCameraLens) {
            CameraLens.FRONT -> AVCaptureDevicePositionFront
            CameraLens.BACK -> AVCaptureDevicePositionBack
        }

        currentCamera =
            findByTypeAndPosition(requestedType, desiredPosition)
                ?: findByTypeAndPosition(requestedType, null)
                ?: when (initialCameraLens) {
                    CameraLens.FRONT -> frontCamera ?: backCamera
                    CameraLens.BACK -> backCamera ?: frontCamera
                }
                ?: return false

        isUsingFrontCamera = (currentCamera == frontCamera)

        return try {
            val input = AVCaptureDeviceInput.deviceInputWithDevice(currentCamera!!, null)
                ?: return false

            if (session?.canAddInput(input) == true) {
                session?.addInput(input)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            throw CameraException.ConfigurationError(e.message ?: "Unknown error")
        }
    }

    /**
     * Queues a configuration change to be applied atomically (Apple WWDC pattern) on the session
     * queue. Used by plugins to safely add outputs without crashing; callable from any thread.
     *
     * A change queued before the session is set up joins the setup transaction. Once the session
     * exists, the change is applied in its own transaction whether the session is running, stopped
     * or interrupted, so an output a plugin removes never waits for a start that may not come.
     *
     * @param change Lambda to execute within beginConfiguration/commitConfiguration block
     */
    fun queueConfigurationChange(change: () -> Unit) {
        dispatch_async(sessionQueue) {
            pendingConfigurations.add(change)
            val ops = session ?: return@dispatch_async
            processPendingConfigurations(ops)
        }
    }

    /**
     * Processes all queued configuration changes in a single transaction. Runs on the session queue,
     * which keeps startRunning out of any open configuration.
     */
    private fun processPendingConfigurations(ops: CaptureSessionOps) {
        if (pendingConfigurations.isEmpty()) return
        ops.beginConfiguration()
        try {
            runPendingConfigurations()
        } finally {
            ops.commitConfiguration()
        }
    }

    /** Runs the queued changes inside the caller's open configuration transaction. */
    private fun runPendingConfigurations() {
        val changesToApply = pendingConfigurations.toList()
        pendingConfigurations.clear()

        for (change in changesToApply) {
            try {
                change()
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "CameraK: Error processing configuration change", e)
            }
        }
    }

    /**
     * Safely adds an output to the capture session.
     * Inside a queueConfigurationChange block it adds the output at once; from any other thread it
     * queues the addition as a configuration change.
     */
    fun safeAddOutput(output: AVCaptureOutput) {
        if (!isOnSessionQueue) {
            queueConfigurationChange { safeAddOutput(output) }
            return
        }
        val session = session ?: return
        if (session.canAddOutput(output)) {
            session.addOutput(output)
        } else {
            CameraKLogger.w(TAG, "CameraK: the capture session refused an output; it was not added")
        }
    }

    /** Whether [output] is attached to the session. Call it on the session queue (inside a change). */
    internal fun containsOutput(output: AVCaptureOutput): Boolean = session?.outputs?.contains(output) == true

    /**
     * Safely removes an output previously added via [safeAddOutput]. Routed through the same
     * [queueConfigurationChange] batching so removal shares one begin/commit transaction with any
     * other pending changes (avoids overlapping/nested configuration transactions). Plugins must
     * call this on detach; a dangling output keeps the pipeline streaming after the plugin is gone.
     */
    fun safeRemoveOutput(output: AVCaptureOutput) {
        queueConfigurationChange {
            val session = session ?: return@queueConfigurationChange
            if (session.outputs.contains(output)) {
                session.removeOutput(output)
            }
        }
    }

    /**
     * Adds the input [create] makes unless an attached input already satisfies [isPresent], as one
     * queued configuration change (the recorder adds its microphone this way). The check, [create]
     * and the add all run on the session queue, so no other session work can interleave with them.
     */
    internal fun addInputUnlessPresent(isPresent: (AVCaptureInput) -> Boolean, create: () -> AVCaptureInput?) {
        queueConfigurationChange {
            val ops = session ?: return@queueConfigurationChange
            if (ops.inputs.any(isPresent)) return@queueConfigurationChange
            val input = create() ?: return@queueConfigurationChange
            if (ops.canAddInput(input)) {
                ops.addInput(input)
            }
        }
    }

    /** Runs [block] on the session queue after every piece of session work already requested. */
    internal fun afterQueuedSessionWork(block: () -> Unit) {
        dispatch_async(sessionQueue, block)
    }

    fun startSession() {
        dispatch_async(sessionQueue) {
            val ops = session ?: return@dispatch_async
            if (!ops.isRunning) {
                ops.startRunning()
            }
        }
    }

    fun stopSession() {
        dispatch_async(sessionQueue) {
            val ops = session ?: return@dispatch_async
            if (ops.isRunning) {
                ops.stopRunning()
            }
        }
    }

    private fun AspectRatio.toSessionPreset(): String = when (this) {
        AspectRatio.RATIO_16_9, AspectRatio.RATIO_9_16 -> (
            AVCaptureSessionPreset1920x1080
                ?: AVCaptureSessionPresetPhoto
            )!!
        AspectRatio.RATIO_1_1 -> AVCaptureSessionPresetPhoto!!
        AspectRatio.RATIO_4_3 -> AVCaptureSessionPresetPhoto!!
    }

    /**
     * Stops and releases the session on the session queue, after any setup already queued, so a setup
     * still in flight cannot leave a running session behind. Call it on the main thread: it removes
     * the preview layer.
     */
    fun cleanupSession() {
        setupGeneration.incrementAndGet()
        cameraPreviewLayer?.removeFromSuperlayer()
        cameraPreviewLayer = null
        dispatch_async(sessionQueue) {
            session?.let { ops ->
                if (ops.isRunning) ops.stopRunning()
            }
            session = null
            photoOutput = null
            currentCamera = null
            backCamera = null
            frontCamera = null
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    fun setupPreviewLayer(view: UIView) {
        val session = session?.avSession ?: return

        val newPreviewLayer = AVCaptureVideoPreviewLayer(session = session).apply {
            // ResizeAspect (letterbox) so the preview shows the exact frame that gets captured — WYSIWYG (#119).
            // ResizeAspectFill would crop the preview to fill the view, mismatching the full-frame captured photo.
            videoGravity = AVLayerVideoGravityResizeAspect
            setFrame(view.bounds)
            connection?.videoOrientation = currentVideoOrientation()
        }

        view.layer.addSublayer(newPreviewLayer)
        cameraPreviewLayer = newPreviewLayer
    }

    @Volatile
    private var lastVideoOrientation: AVCaptureVideoOrientation = AVCaptureVideoOrientationPortrait

    fun currentVideoOrientation(): AVCaptureVideoOrientation {
        // FaceUp / FaceDown / Unknown don't correspond to a video orientation. Mapping them to
        // Portrait snaps a landscape preview to portrait and distorts it when the device lies flat
        // (#115). Keep the last valid orientation in those cases so portrait/landscape tracking
        // stays stable as the device tilts (#109).
        lastVideoOrientation = when (UIDevice.currentDevice.orientation) {
            UIDeviceOrientation.UIDeviceOrientationPortrait -> AVCaptureVideoOrientationPortrait
            UIDeviceOrientation.UIDeviceOrientationPortraitUpsideDown -> AVCaptureVideoOrientationPortraitUpsideDown
            UIDeviceOrientation.UIDeviceOrientationLandscapeLeft -> AVCaptureVideoOrientationLandscapeRight
            UIDeviceOrientation.UIDeviceOrientationLandscapeRight -> AVCaptureVideoOrientationLandscapeLeft
            else -> lastVideoOrientation
        }
        return lastVideoOrientation
    }

    fun setFlashMode(mode: AVCaptureFlashMode) {
        flashMode = supportedFlashMode(mode)
        if (flashMode != mode) {
            CameraKLogger.e("CameraK", "CameraK: Flash mode not supported on this device, using OFF")
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    fun setTorchMode(mode: AVCaptureTorchMode) {
        torchMode = mode
        currentCamera?.let { camera ->
            if (camera.hasTorch) {
                try {
                    camera.lockForConfiguration(null)
                    camera.torchMode = mode
                    camera.unlockForConfiguration()
                } catch (e: Exception) {
                    onError?.invoke(CameraException.ConfigurationError("Failed to set torch mode"))
                }
            }
        }
    }

    /**
     * Sets the zoom level smoothly.
     * @param zoomFactor The desired zoom level (1.0 = no zoom)
     */
    @OptIn(ExperimentalForeignApi::class)
    fun setZoom(zoomFactor: Float) {
        currentCamera?.let { camera ->
            val clampedZoom = zoomFactor.coerceIn(1.0f, getMaxZoom())
            try {
                camera.lockForConfiguration(null)
                camera.videoZoomFactor = clampedZoom.toDouble()
                camera.unlockForConfiguration()
            } catch (e: Exception) {
                onError?.invoke(CameraException.ConfigurationError("Failed to set zoom: ${e.message}"))
            }
        }
    }

    /**
     * Gets the current zoom level.
     * @return Current zoom factor (1.0 = no zoom)
     */
    fun getZoom(): Float = currentCamera?.videoZoomFactor?.toFloat() ?: 1.0f

    /**
     * Gets the maximum zoom level supported by the current camera.
     * @return Maximum zoom factor
     */
    fun getMaxZoom(): Float = currentCamera?.activeFormat?.videoMaxZoomFactor?.toFloat() ?: 1.0f

    /**
     * Capture an image. Output quality is governed by the configured [qualityPrioritization].
     */
    fun captureImage() {
        if (photoOutput == null || session?.isRunning != true) {
            onError?.invoke(CameraException.ConfigurationError("Camera not ready for capture"))
            return
        }

        // Do NOT reconfigure the session preset here. Switching the preset synchronously right
        // before capturePhotoWithSettings disrupts auto-exposure, so the still is captured
        // mid-reconfiguration and comes out underexposed (#138). The preset is chosen once at
        // setup; memory pressure is handled by clearing buffer pools, not by downshifting capture.

        val settings = AVCapturePhotoSettings.photoSettingsWithFormat(
            mapOf(
                AVVideoCodecKey to AVVideoCodecJPEG,
            ),
        )

        settings.setHighResolutionPhotoEnabled(highResolution)
        avQualityPrioritization?.let { settings.photoQualityPrioritization = it }
        settings.flashMode = supportedFlashMode(flashMode)

        // Don't touch autoStillImageStabilizationEnabled: it's deprecated since iOS 13 and
        // setting it together with photoQualityPrioritization (set above) raises -17281
        // (invalid state). Stabilization is handled automatically by the prioritization level. (#113)

        // Set the photo output connection orientation to match current device orientation
        // This ensures the captured photo has the correct orientation metadata
        photoOutput?.connectionWithMediaType(AVMediaTypeVideo)?.let { connection ->
            if (connection.isVideoOrientationSupported()) {
                connection.videoOrientation = currentVideoOrientation()
            }
            // Mirror the front-camera photo to match the mirrored preview when configured (#112).
            if (connection.isVideoMirroringSupported()) {
                connection.automaticallyAdjustsVideoMirroring = false
                connection.videoMirrored = mirrorFrontCamera && isUsingFrontCamera
            }
        }

        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH.toLong(), 0u)) {
            photoOutput?.capturePhotoWithSettings(settings, delegate = this)
        }
    }

    /**
     * Switches to a specific camera device type (e.g. wide-angle, telephoto, ultra-wide)
     * while keeping the same camera position (front/back).
     */
    @OptIn(ExperimentalForeignApi::class)
    fun switchToDeviceType(deviceType: CameraDeviceType) {
        dispatch_async(sessionQueue) {
            val ops = session ?: return@dispatch_async
            val targetType = deviceType.toAVCaptureDeviceType() ?: return@dispatch_async

            // Determine current position
            val position = if (isUsingFrontCamera) {
                AVCaptureDevicePositionFront
            } else {
                AVCaptureDevicePositionBack
            }

            // Discover device matching the requested type and position, falling back to any position
            val newDevice = discoverDevices(listOf(targetType), position).firstOrNull()
                ?: discoverDevices(listOf(targetType), AVCaptureDevicePositionUnspecified).firstOrNull()
                ?: return@dispatch_async

            val failure = reconfigureVideoInput(ops) {
                // Remove current input
                ops.inputs.firstOrNull()?.let(ops::removeInput)

                val newInput = AVCaptureDeviceInput.deviceInputWithDevice(newDevice, null)
                    ?: throw Exception("Failed to create input for device type")

                if (ops.canAddInput(newInput)) {
                    ops.addInput(newInput)
                    currentCamera = newDevice
                    if (newDevice.position == AVCaptureDevicePositionBack) {
                        backCamera = newDevice
                    } else {
                        frontCamera = newDevice
                    }
                }
            }
            if (failure != null) {
                CameraKLogger.e(TAG, "CameraK: switching camera device type failed", failure)
            } else {
                applyPreviewMirroring()
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    fun switchCamera() {
        dispatch_async(sessionQueue) {
            val ops = session ?: return@dispatch_async
            val failure = reconfigureVideoInput(ops) {
                ops.inputs.firstOrNull()?.let(ops::removeInput)

                isUsingFrontCamera = !isUsingFrontCamera
                val switcher = lensSwitcher
                if (switcher != null) {
                    switcher(ops, isUsingFrontCamera)
                } else {
                    currentCamera = if (isUsingFrontCamera) frontCamera else backCamera

                    val newCamera = currentCamera ?: throw CameraException.DeviceNotAvailable()

                    val newInput = AVCaptureDeviceInput.deviceInputWithDevice(
                        newCamera,
                        null,
                    ) ?: throw CameraException.ConfigurationError("Failed to create input")

                    if (ops.canAddInput(newInput)) {
                        ops.addInput(newInput)
                    } else {
                        throw CameraException.ConfigurationError("Cannot add input")
                    }
                }
            }
            if (failure != null) {
                CameraKLogger.e(TAG, "CameraK: switching camera failed", failure)
                reportError(
                    failure as? CameraException
                        ?: CameraException.ConfigurationError(failure.message ?: "Unknown error"),
                )
            } else {
                applyPreviewMirroring()
            }
        }
    }

    /**
     * Runs [swap] in one configuration transaction on the session queue, stopping a running session
     * around it, then applies queued changes and restarts. Returns what [swap] threw, if anything,
     * after the transaction is committed and the session restarted.
     */
    private fun reconfigureVideoInput(ops: CaptureSessionOps, swap: () -> Unit): Exception? {
        val wasRunning = ops.isRunning
        if (wasRunning) {
            ops.stopRunning()
        }
        ops.beginConfiguration()
        val failure = try {
            swap()
            null
        } catch (e: Exception) {
            e
        } finally {
            ops.commitConfiguration()
        }
        processPendingConfigurations(ops)
        if (wasRunning) {
            ops.startRunning()
        }
        return failure
    }

    /**
     * Mirrors the preview for the lens now in use. Called on the session queue after a swap; the
     * preview layer belongs to UIKit, so it is read and changed on main (AVCam does the same).
     */
    private fun applyPreviewMirroring() {
        val mirrored = isUsingFrontCamera
        dispatch_async(dispatch_get_main_queue()) {
            previewMirroring(cameraPreviewLayer, mirrored)
        }
    }

    private fun mirrorPreview(layer: AVCaptureVideoPreviewLayer?, mirrored: Boolean) {
        layer?.connection?.let { connection ->
            if (connection.isVideoMirroringSupported()) {
                connection.automaticallyAdjustsVideoMirroring = false
                connection.setVideoMirrored(mirrored)
            }
        }
    }

    override fun captureOutput(
        output: AVCapturePhotoOutput,
        didFinishProcessingPhoto: AVCapturePhoto,
        error: NSError?,
    ) {
        if (error != null) {
            onError?.invoke(CameraException.CaptureError(error.localizedDescription))
            return
        }

        val imageData = didFinishProcessingPhoto.fileDataRepresentation()
        onPhotoCapture?.invoke(imageData)
    }
}

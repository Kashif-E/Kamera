package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.cinterop.ExperimentalForeignApi
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
import platform.darwin.dispatch_queue_create
import kotlin.collections.emptyList
import kotlin.concurrent.Volatile

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
    var captureSession: AVCaptureSession? = null
    private var backCamera: AVCaptureDevice? = null
    private var frontCamera: AVCaptureDevice? = null
    private var currentCamera: AVCaptureDevice? = null
    private var photoOutput: AVCapturePhotoOutput? = null
    var cameraPreviewLayer: AVCaptureVideoPreviewLayer? = null
    private var isUsingFrontCamera = false

    var onPhotoCapture: ((NSData?) -> Unit)? = null
    var onError: ((CameraException) -> Unit)? = null
    var onSessionReady: (() -> Unit)? = null

    var flashMode: AVCaptureFlashMode = AVCaptureFlashModeAuto
    var torchMode: AVCaptureTorchMode = AVCaptureTorchModeAuto

    // Configuration queue for plugin outputs (Apple WWDC pattern)
    private val pendingConfigurations = mutableListOf<() -> Unit>()

    @Volatile
    private var isConfiguring = false

    // startRunning() and stopRunning() block until the session changes state, so they must not run on
    // the main thread. A serial queue also keeps a quick stop -> start (or start -> stop) in call order,
    // which the concurrent global queue did not guarantee (#162).
    private val sessionQueue = dispatch_queue_create("com.kashif.cameraK.session", null)

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
        try {
            // Perform heavy setup off the main thread to reduce UI stalls (#73)
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH.toLong(), 0u)) {
                captureSession = AVCaptureSession()
                captureSession?.beginConfiguration()

                val resolutionPreset = targetResolutionPreset()

                // Start with a fast preset; prefer target resolution if provided
                val initialPreset = resolutionPreset ?: AVCaptureSessionPresetHigh
                captureSession?.sessionPreset = initialPreset

                if (!setupInputs(cameraDeviceType)) {
                    dispatch_async(dispatch_get_main_queue()) {
                        cleanupSession()
                        onError?.invoke(CameraException.DeviceNotAvailable())
                    }
                    return@dispatch_async
                }

                setupPhotoOutput()
                captureSession?.commitConfiguration()

                // Switch to target resolution/aspect ratio preset on main queue once initial setup completes
                dispatch_async(dispatch_get_main_queue()) {
                    captureSession?.beginConfiguration()
                    val finalPreset = resolutionPreset ?: aspectRatio.toSessionPreset()
                    captureSession?.sessionPreset = finalPreset
                    captureSession?.commitConfiguration()
                    captureSession?.commitConfiguration()
                    onSessionReady?.invoke()
                }
            }
        } catch (e: CameraException) {
            cleanupSession()
            onError?.invoke(e)
        }
    }

    /**
     * The session preset for [targetResolution], or null to use [aspectRatio]'s own preset.
     *
     * The aspect ratio is the primary constraint, as on Android (#136). Every size-specific preset
     * AVFoundation offers is 16:9, so a target resolution can only choose among them when a 16:9 or
     * 9:16 ratio is configured. For 4:3 and 1:1 it is ignored with a warning, instead of silently
     * replacing the requested field of view with a narrower 16:9 one (#161).
     */
    private fun targetResolutionPreset(): String? {
        val target = targetResolution ?: return null
        if (aspectRatio != AspectRatio.RATIO_16_9 && aspectRatio != AspectRatio.RATIO_9_16) {
            CameraKLogger.w(
                "CameraK",
                "targetResolution ${target.first}x${target.second} ignored on iOS: its session presets are " +
                    "all 16:9, which would override aspectRatio $aspectRatio",
            )
            return null
        }
        return target.toPreset()
    }

    private fun Pair<Int, Int>.toPreset(): String? {
        // Compare long and short edges, so a portrait target (e.g. 1080x1920 for RATIO_9_16) matches too.
        val long = maxOf(first, second)
        val short = minOf(first, second)
        return when {
            long >= 3840 && short >= 2160 -> AVCaptureSessionPreset3840x2160
            long >= 1920 && short >= 1080 -> AVCaptureSessionPreset1920x1080
            long >= 1280 && short >= 720 -> AVCaptureSessionPreset1280x720
            else -> null
        }
    }

    private fun setupPhotoOutput() {
        photoOutput = AVCapturePhotoOutput()
        photoOutput?.setHighResolutionCaptureEnabled(false)

        when (qualityPrioritization) {
            QualityPrioritization.QUALITY -> {
                photoOutput?.setHighResolutionCaptureEnabled(true)
                photoOutput?.setMaxPhotoQualityPrioritization(
                    AVCapturePhotoQualityPrioritizationQuality,
                )
            }

            QualityPrioritization.BALANCED -> photoOutput?.setMaxPhotoQualityPrioritization(
                AVCapturePhotoQualityPrioritizationBalanced,
            )

            QualityPrioritization.SPEED -> photoOutput?.setMaxPhotoQualityPrioritization(
                AVCapturePhotoQualityPrioritizationSpeed,
            )

            QualityPrioritization.NONE -> null
        }

        photoOutput?.setPreparedPhotoSettingsArray(emptyList<String>(), completionHandler = { settings, error ->
            if (error != null) {
                onError?.invoke(CameraException.ConfigurationError(error.localizedDescription))
            }
        })

        if (captureSession?.canAddOutput(photoOutput!!) == true) {
            captureSession?.addOutput(photoOutput!!)
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
     * The device to bind for [deviceType] at [position], or null if that position has none.
     *
     * [CameraDeviceType.DEFAULT] prefers the ultra-wide on the front. The iPhone 17 family's Center Stage
     * front camera (a square, portrait-mounted sensor) is exposed as builtInUltraWideCamera, and binding
     * the front wide-angle device there instead squashes the live preview (#170). Devices without a front
     * ultra-wide keep the wide-angle camera.
     */
    private fun preferredDevice(deviceType: CameraDeviceType, position: AVCaptureDevicePosition): AVCaptureDevice? {
        val types = if (deviceType == CameraDeviceType.DEFAULT && position == AVCaptureDevicePositionFront) {
            listOfNotNull(AVCaptureDeviceTypeBuiltInUltraWideCamera, AVCaptureDeviceTypeBuiltInWideAngleCamera)
        } else {
            listOfNotNull(deviceType.toAVCaptureDeviceType())
        }
        if (types.isEmpty()) return null
        val devices = discoverDevices(types, position)
        // The discovery session doesn't promise an order, so apply the preference explicitly.
        return types.firstNotNullOfOrNull { type -> devices.firstOrNull { it.deviceType == type } }
    }

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

        val discovered = if (cameraDeviceType == CameraDeviceType.DEFAULT) {
            // DEFAULT resolves per position (see preferredDevice), so front and back can differ in type.
            listOfNotNull(
                preferredDevice(cameraDeviceType, AVCaptureDevicePositionBack),
                preferredDevice(cameraDeviceType, AVCaptureDevicePositionFront),
            )
        } else {
            discoverDevices(
                deviceTypeString?.let { listOf(it) } ?: allLensDeviceTypes(),
                AVCaptureDevicePositionUnspecified,
            )
        }
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

        // DEFAULT's devices were already chosen per position, so match on position alone.
        val requestedType = if (cameraDeviceType == CameraDeviceType.DEFAULT) null else deviceTypeString
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

            if (captureSession?.canAddInput(input) == true) {
                captureSession?.addInput(input)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            throw CameraException.ConfigurationError(e.message ?: "Unknown error")
        }
    }

    /**
     * Queues a configuration change to be applied atomically (Apple WWDC pattern).
     * Used by plugins to safely add outputs without crashing.
     *
     * If session is already running, processes configurations immediately.
     * Otherwise queues for batch processing at startSession().
     *
     * @param change Lambda to execute within beginConfiguration/commitConfiguration block
     */
    fun queueConfigurationChange(change: () -> Unit) {
        pendingConfigurations.add(change)

        // If session is already running, process immediately
        if (captureSession?.isRunning() == true && !isConfiguring) {
            processPendingConfigurations()
        }
    }

    /**
     * Processes all queued configuration changes in a single transaction.
     * Must be called on main thread or after session is ready.
     * Prevents "startRunning may not be called between beginConfiguration and commitConfiguration" crash.
     */
    private fun processPendingConfigurations() {
        if (isConfiguring || pendingConfigurations.isEmpty() || captureSession == null) {
            return
        }

        isConfiguring = true

        try {
            val session = captureSession ?: return

            session.beginConfiguration()

            val changesToApply = pendingConfigurations.toList()
            pendingConfigurations.clear()

            for (change in changesToApply) {
                try {
                    change()
                } catch (e: Exception) {
                    CameraKLogger.e("CameraK", "CameraK: Error processing configuration change: ${e.message}")
                }
            }

            session.commitConfiguration()
        } finally {
            isConfiguring = false
        }
    }

    /**
     * Safely adds an output to the capture session.
     * Should be called from within queueConfigurationChange block.
     */
    fun safeAddOutput(output: AVCaptureOutput) {
        val session = captureSession
        if (session != null && session.canAddOutput(output)) {
            session.addOutput(output)
        }
    }

    /**
     * Safely removes an output previously added via [safeAddOutput]. Routed through the same
     * [queueConfigurationChange] batching so removal shares one begin/commit transaction with any
     * other pending changes (avoids overlapping/nested configuration transactions). Plugins must
     * call this on detach; a dangling output keeps the pipeline streaming after the plugin is gone.
     */
    fun safeRemoveOutput(output: AVCaptureOutput) {
        queueConfigurationChange {
            val session = captureSession ?: return@queueConfigurationChange
            if (session.outputs.contains(output)) {
                session.removeOutput(output)
            }
        }
    }

    fun startSession() {
        processPendingConfigurations()

        val session = captureSession ?: return
        dispatch_async(sessionQueue) {
            if (!session.isRunning()) {
                session.startRunning()
            }
        }
    }

    /**
     * Stops the session without blocking the caller. The stop runs on [sessionQueue], after any start
     * queued before it, on the session captured here, so [cleanupSession] can drop its reference at once.
     */
    fun stopSession() {
        val session = captureSession ?: return
        dispatch_async(sessionQueue) {
            if (session.isRunning()) {
                session.stopRunning()
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

    fun cleanupSession() {
        // Asynchronous, and bound to the current session, so clearing captureSession below can't race it.
        // The preview layer is removed here, on the caller's (main) thread, as CALayer work must be.
        stopSession()
        cameraPreviewLayer?.removeFromSuperlayer()
        cameraPreviewLayer = null
        captureSession = null
        photoOutput = null
        currentCamera = null
        backCamera = null
        frontCamera = null
    }

    @OptIn(ExperimentalForeignApi::class)
    fun setupPreviewLayer(view: UIView) {
        val session = captureSession ?: return

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
        // Check if device supports this flash mode before setting
        val supportedFlashModes = photoOutput?.supportedFlashModes() as? List<*>
        if (supportedFlashModes?.contains(mode) == true) {
            flashMode = mode
        } else {
            // Device doesn't support flash (e.g., iPad) - use OFF
            CameraKLogger.e("CameraK", "CameraK: Flash mode not supported on this device, using OFF")
            flashMode = AVCaptureFlashModeOff
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
        if (photoOutput == null || captureSession?.isRunning() != true) {
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

        settings.setHighResolutionPhotoEnabled(false)

        when (qualityPrioritization) {
            QualityPrioritization.QUALITY -> {
                settings.setHighResolutionPhotoEnabled(true)
                settings.photoQualityPrioritization = AVCapturePhotoQualityPrioritizationQuality
            }

            QualityPrioritization.BALANCED -> {
                settings.photoQualityPrioritization = AVCapturePhotoQualityPrioritizationBalanced
            }

            QualityPrioritization.SPEED -> {
                settings.photoQualityPrioritization = AVCapturePhotoQualityPrioritizationSpeed
            }

            QualityPrioritization.NONE -> null
        }

        // Only set flash mode if supported by device (iPads don't have flash)
        val supportedFlashModes = photoOutput?.supportedFlashModes() as? List<*>
        if (supportedFlashModes?.contains(this.flashMode) == true) {
            settings.flashMode = this.flashMode
        } else {
            // Device doesn't support flash (e.g., iPad) - force OFF
            settings.flashMode = AVCaptureFlashModeOff
        }

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
     *
     * The switch is transactional: the replacement input is created before the current one is touched,
     * and if the session won't accept it the previous input is restored, instead of leaving the session
     * without a camera (#170). Failures are logged, not sent to [onError]: a capture in flight listens
     * there and would fail.
     *
     * @return true if the requested device type is bound when this returns.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun switchToDeviceType(deviceType: CameraDeviceType): Boolean {
        val session = captureSession ?: return false
        val position = if (isUsingFrontCamera) AVCaptureDevicePositionFront else AVCaptureDevicePositionBack

        // Stay on the current position. Binding the other position's lens would leave isUsingFrontCamera,
        // the mirroring and the lens reported to the app describing a camera that isn't bound.
        val newDevice = preferredDevice(deviceType, position) ?: run {
            CameraKLogger.w("CameraK", "No $deviceType camera at the current position")
            return false
        }
        // Already bound, e.g. DEFAULT requested again after a lens switch: nothing to reconfigure.
        if (newDevice == currentCamera) return true

        val newInput = AVCaptureDeviceInput.deviceInputWithDevice(newDevice, null) ?: run {
            CameraKLogger.w("CameraK", "Failed to create input for $deviceType")
            return false
        }
        val oldInput = session.inputs
            .mapNotNull { it as? AVCaptureDeviceInput }
            .firstOrNull { it.device.hasMediaType(AVMediaTypeVideo) }

        // Inputs can be swapped on a running session inside one configuration transaction, so the
        // session is not stopped and restarted on the caller's thread.
        session.beginConfiguration()
        oldInput?.let { session.removeInput(it) }
        val switched = session.canAddInput(newInput)
        if (switched) {
            session.addInput(newInput)
            cameraPreviewLayer?.connection?.let { connection ->
                if (connection.isVideoMirroringSupported()) {
                    connection.automaticallyAdjustsVideoMirroring = false
                    connection.setVideoMirrored(isUsingFrontCamera)
                }
            }
        } else if (oldInput != null && session.canAddInput(oldInput)) {
            session.addInput(oldInput)
        }
        session.commitConfiguration()

        if (!switched) {
            CameraKLogger.w("CameraK", "Cannot add input for $deviceType")
            return false
        }

        currentCamera = newDevice
        if (position == AVCaptureDevicePositionBack) {
            backCamera = newDevice
        } else {
            frontCamera = newDevice
        }
        return true
    }

    @OptIn(ExperimentalForeignApi::class)
    fun switchCamera() {
        guard(captureSession != null) { return@guard }

        // Inputs can be swapped on a running session inside one configuration transaction, so the
        // session is not stopped (a blocking call) on the caller's thread.
        captureSession?.beginConfiguration()

        try {
            // Remove the camera input specifically; a mic input may also be attached.
            val videoInput = captureSession?.inputs?.firstOrNull { input ->
                (input as? AVCaptureDeviceInput)?.device?.hasMediaType(AVMediaTypeVideo) == true
            }
            videoInput?.let { input ->
                captureSession?.removeInput(input as AVCaptureInput)
            }

            isUsingFrontCamera = !isUsingFrontCamera
            currentCamera = if (isUsingFrontCamera) frontCamera else backCamera

            val newCamera = currentCamera ?: throw CameraException.DeviceNotAvailable()

            val newInput = AVCaptureDeviceInput.deviceInputWithDevice(
                newCamera,
                null,
            ) ?: throw CameraException.ConfigurationError("Failed to create input")

            if (captureSession?.canAddInput(newInput) == true) {
                captureSession?.addInput(newInput)
            } else {
                throw CameraException.ConfigurationError("Cannot add input")
            }

            cameraPreviewLayer?.connection?.let { connection ->
                if (connection.isVideoMirroringSupported()) {
                    connection.automaticallyAdjustsVideoMirroring = false
                    connection.setVideoMirrored(isUsingFrontCamera)
                }
            }

            captureSession?.commitConfiguration()

            processPendingConfigurations()
        } catch (e: CameraException) {
            captureSession?.commitConfiguration()
            onError?.invoke(e)
        } catch (e: Exception) {
            captureSession?.commitConfiguration()
            onError?.invoke(CameraException.ConfigurationError(e.message ?: "Unknown error"))
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

    private inline fun guard(condition: Boolean, crossinline block: () -> Unit) {
        if (!condition) block()
    }
}

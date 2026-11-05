@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.utils.InternalCameraKApi
import com.kashif.cameraK.utils.toByteArray
import com.kashif.cameraK.video.VideoCaptureResult
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFoundation.AVCaptureInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureVideoOrientation
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.CoreGraphics.CGRect
import platform.Foundation.NSData
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSError
import platform.Foundation.writeToFile
import platform.UIKit.UIInterfaceOrientation
import platform.darwin.DISPATCH_QUEUE_PRIORITY_HIGH
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import kotlin.concurrent.Volatile

/**
 * The 1.x iOS camera, kept until 2.0's C1' as a facade over an [AppleCameraEngine]. It is a plain
 * class, not a UIViewController: the engine owns the session, [startSession] starts it with no view,
 * and the preview is a layer camerak's CameraPreviewView hosts ([createPreviewLayer]).
 *
 * Built through a public constructor or the builder, it builds an engine of its own; the bridge's
 * engine builds one over itself. Members that open, stop or switch the camera go through the engine,
 * and so do the flash, torch and zoom setters while the camera is Open; before that the facade keeps
 * them and its startSession passes them to the engine's start. The target and device orientation are
 * the engine's. Captures and recordings are the engine's too, mapped to 1.x results. Analyzers keep
 * their 1.x bodies on the session the engine owns, until I4' gives the engine that path.
 */
actual class CameraController private constructor(
    flashMode: FlashMode,
    torchMode: TorchMode,
    @Volatile internal var cameraLens: CameraLens,
    internal var imageFormat: ImageFormat,
    internal var qualityPriority: QualityPrioritization,
    internal var directory: Directory,
    @Volatile internal var cameraDeviceType: CameraDeviceType,
    internal var aspectRatio: AspectRatio,
    internal var targetResolution: Pair<Int, Int>?,
    internal var mirrorFrontCamera: Boolean,
    customController: CustomCameraController?,
    engine: AppleCameraEngine?,
) {
    /** Test seam: a controller built on a fake session and camera, with an engine of its own over it. */
    internal constructor(
        flashMode: FlashMode,
        torchMode: TorchMode,
        cameraLens: CameraLens,
        imageFormat: ImageFormat,
        qualityPriority: QualityPrioritization,
        directory: Directory,
        cameraDeviceType: CameraDeviceType,
        aspectRatio: AspectRatio,
        targetResolution: Pair<Int, Int>?,
        mirrorFrontCamera: Boolean,
        customController: CustomCameraController?,
    ) : this(
        flashMode,
        torchMode,
        cameraLens,
        imageFormat,
        qualityPriority,
        directory,
        cameraDeviceType,
        aspectRatio,
        targetResolution,
        mirrorFrontCamera,
        customController,
        engine = null,
    )

    /** The facade [engine] carries to Ready.controller, configured as the engine is. */
    internal constructor(engine: AppleCameraEngine, config: CameraConfiguration) : this(
        flashMode = config.flashMode,
        torchMode = config.torchMode,
        cameraLens = config.cameraLens,
        imageFormat = config.imageFormat,
        qualityPriority = config.qualityPrioritization,
        directory = config.directory,
        cameraDeviceType = config.cameraDeviceType,
        aspectRatio = config.aspectRatio,
        targetResolution = config.targetResolution,
        mirrorFrontCamera = config.mirrorFrontCamera,
        customController = null,
        engine = engine,
    )

    constructor(
        flashMode: FlashMode,
        torchMode: TorchMode,
        cameraLens: CameraLens,
        imageFormat: ImageFormat,
        qualityPriority: QualityPrioritization,
        directory: Directory,
        cameraDeviceType: CameraDeviceType,
        aspectRatio: AspectRatio,
        targetResolution: Pair<Int, Int>? = null,
        mirrorFrontCamera: Boolean = false,
    ) : this(
        flashMode,
        torchMode,
        cameraLens,
        imageFormat,
        qualityPriority,
        directory,
        cameraDeviceType,
        aspectRatio,
        targetResolution,
        mirrorFrontCamera,
        customController = null,
        engine = null,
    )

    /** The engine this facade runs on: its own, unless the engine built the facade over itself. */
    internal val engine: AppleCameraEngine = engine ?: AppleCameraEngine(
        config = CameraConfiguration(
            flashMode = flashMode,
            torchMode = torchMode,
            cameraLens = cameraLens,
            imageFormat = imageFormat,
            qualityPrioritization = qualityPriority,
            cameraDeviceType = cameraDeviceType,
            aspectRatio = aspectRatio,
            targetResolution = targetResolution,
            directory = directory,
            mirrorFrontCamera = mirrorFrontCamera,
        ),
        environment = CameraKEnvironment(),
        custom = customController ?: CustomCameraController(
            qualityPrioritization = qualityPriority,
            initialCameraLens = cameraLens,
            aspectRatio = aspectRatio,
            targetResolution = targetResolution,
            mirrorFrontCamera = mirrorFrontCamera,
        ),
    ).also { it.adopt(this) }

    private val customCameraController: CustomCameraController get() = engine.custom

    /**
     * The flash, torch and zoom this facade wants, kept in any state and passed to the engine's start;
     * its selection and target orientation are not read (the lens fields and the engine hold those).
     */
    private val desired = atomic(
        DesiredHardware(
            selection = CameraSelection.ByLens(cameraLens, cameraDeviceType),
            flashMode = flashMode,
            torchMode = torchMode,
            zoomRatio = 1f,
            targetOrientation = null,
        ),
    )

    private fun updateDesired(flashMode: FlashMode? = null, torchMode: TorchMode? = null, zoomRatio: Float? = null) =
        desired.update { current ->
            DesiredHardware(
                selection = current.selection,
                flashMode = flashMode ?: current.flashMode,
                torchMode = torchMode ?: current.torchMode,
                zoomRatio = zoomRatio ?: current.zoomRatio,
                targetOrientation = null,
            )
        }
    private var imageCaptureListeners = mutableListOf<(ByteArray) -> Unit>()
    private var metadataOutput = AVCaptureMetadataOutput()
    private var metadataObjectsDelegate: AVCaptureMetadataOutputObjectsDelegateProtocol? = null

    /** Scanner callbacks run here, one at a time and off main, so scanning never competes with the UI. */
    private val metadataQueue = dispatch_queue_create("com.kashif.cameraK.metadata", null)

    /** Test seam: the movie-file-output calls a recording makes (the engine's). */
    internal var movieRecorder: MovieRecorder
        get() = engine.movieRecorder
        set(value) {
            engine.movieRecorder = value
        }

    /** Test seam: the microphone's session input for a recording with audio, or why there is none. */
    internal var microphoneInput: () -> Result<AVCaptureInput>
        get() = engine.microphoneInput
        set(value) {
            engine.microphoneInput = value
        }

    /** Test seam: whether the app's Info.plist has a key (NSMicrophoneUsageDescription for audio). */
    internal var hasUsageDescription: (String) -> Boolean
        get() = engine.recorder.hasUsageDescription
        set(value) {
            engine.recorder.hasUsageDescription = value
        }

    /** How long startRecording waits for AVFoundation to report the recording started. */
    internal var recordingStartTimeoutMs: Long
        get() = engine.recorder.startTimeoutMs
        set(value) {
            engine.recorder.startTimeoutMs = value
        }

    /** How long stopRecording waits for AVFoundation to finish the file. */
    internal var recordingFinishTimeoutMs: Long
        get() = engine.recorder.finishTimeoutMs
        set(value) {
            engine.recorder.finishTimeoutMs = value
        }

    /** Whether the movie output can pause; AVCaptureFileOutput's pause exists from iOS 18. */
    internal var pauseSupported: () -> Boolean
        get() = engine.recorder.pauseSupported
        set(value) {
            engine.recorder.pauseSupported = value
        }

    init {
        // The configured flash, not CustomCameraController's default, is what captures use until the
        // engine's start applies the desired hardware.
        customCameraController.setFlashMode(flashMode.toAVCaptureFlashMode())
        customCameraController.onPhotoCapture = { image -> image?.let(::processImageCapture) }
        this.engine.stillListener = ::processImageCapture
        customCameraController.onError = { error ->
            CameraKLogger.e("CameraK", "CameraK: the camera reported an error", error)
        }
        // The metadata output joins the session only while a scanner is set, and the movie output only
        // for a recording: each costs memory and power and limits the formats the session can use.
    }

    fun getCameraPreviewLayer() = customCameraController.cameraPreviewLayer

    /**
     * A preview layer bound to the engine's session, mirrored for the front camera; camerak's
     * CameraPreviewView hosts it. Main thread only.
     */
    @InternalCameraKApi
    fun createPreviewLayer(): AVCaptureVideoPreviewLayer = engine.preview.createPreviewLayer()

    /**
     * The view hosting the preview laid out again, under an interface in [orientation] (null when it
     * has no window scene yet): the preview turns to the interface. Main thread only.
     */
    @InternalCameraKApi
    fun previewLayoutChanged(orientation: UIInterfaceOrientation?) {
        hostInterfaceOrientation = orientation?.toVideoOrientation()
        applyPreviewOrientation()
    }

    /**
     * Lays out [layer], one [createPreviewLayer] made, in a host view whose bounds are [bounds] under
     * an interface in [orientation], as the engine's own preview does. Main thread only.
     */
    @InternalCameraKApi
    @OptIn(ExperimentalForeignApi::class)
    fun layOutPreviewLayer(
        layer: AVCaptureVideoPreviewLayer,
        bounds: CValue<CGRect>,
        orientation: UIInterfaceOrientation?,
    ) {
        hostInterfaceOrientation = orientation?.toVideoOrientation()
        engine.preview.layOutPreviewLayer(layer, bounds, orientation)
    }

    /** The interface orientation the preview's host last reported. Main thread only. */
    private var hostInterfaceOrientation: AVCaptureVideoOrientation? = null

    /**
     * Returns whether the capture session is ready for use.
     * Used by plugins to check if they can add outputs.
     */
    fun isSessionReady(): Boolean = customCameraController.hasSession

    /**
     * Queues a configuration change to be applied atomically (plugin outputs, etc).
     * Used by plugins (OCR, QRScanner) to safely add outputs without crashes.
     */
    fun queueConfigurationChange(change: () -> Unit) {
        customCameraController.queueConfigurationChange(change)
    }

    /**
     * Safely adds an output within the queued configuration block.
     */
    fun safeAddOutput(output: AVCaptureOutput) {
        customCameraController.safeAddOutput(output)
    }

    /**
     * Safely removes an output previously added via [safeAddOutput].
     */
    fun safeRemoveOutput(output: AVCaptureOutput) {
        customCameraController.safeRemoveOutput(output)
    }

    private fun processImageCapture(imageData: NSData) {
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH.toLong(), 0u)) {
            autoreleasepool {
                try {
                    // An array of the photo's exact size that only the listeners hold: a pooled buffer
                    // is as large as the biggest earlier photo and goes back to the pool while they read.
                    val data = imageData.toByteArray()

                    dispatch_async(dispatch_get_main_queue()) {
                        for (listener in imageCaptureListeners.toList()) {
                            try {
                                listener(data)
                            } catch (e: Exception) {
                                CameraKLogger.e("CameraK", "CameraK: an image capture listener failed", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    CameraKLogger.e("CameraK", "CameraK: copying the captured photo for listeners failed", e)
                }
            }
        }
    }

    /**
     * Sets the scanner's delegate and attaches the metadata output, as a queued change, so a change
     * the scanner queues next (setting its object types) finds the output attached. The delegate is
     * called on a serial background queue, not on main; hop to main before touching UI state.
     */
    fun setMetadataObjectsDelegate(delegate: AVCaptureMetadataOutputObjectsDelegateProtocol) {
        metadataObjectsDelegate = delegate
        metadataOutput.setMetadataObjectsDelegate(delegate, metadataQueue)
        customCameraController.queueConfigurationChange {
            if (!customCameraController.containsOutput(metadataOutput)) {
                customCameraController.safeAddOutput(metadataOutput)
            }
        }
    }

    /**
     * Clears the metadata delegate so QR/barcode callbacks stop. Used by the QR plugin on detach.
     */
    fun clearMetadataObjectsDelegate() {
        metadataObjectsDelegate = null
        metadataOutput.setMetadataObjectsDelegate(null, metadataQueue)
        customCameraController.safeRemoveOutput(metadataOutput)
    }

    fun updateMetadataObjectTypes(newTypes: List<String>) {
        // Only ever assign the intersection with availableMetadataObjectTypes. Assigning a type the
        // hardware doesn't advertise throws NSInvalidArgumentException (SIGABRT) inside
        // _buildAndRunGraph — most visible on Mac Catalyst, where the camera advertises far fewer
        // barcode types than an iPhone (and sometimes none). Never force-set the full list. (#70)
        val available = metadataOutput.availableMetadataObjectTypes
        val supportedTypes = newTypes.filter { available.contains(it) }
        // Nothing supported yet: the output isn't connected, or the platform (some Macs) advertises
        // no barcode metadata types at all. Leave types unset — scanning is a no-op, not a crash.
        // (A Kotlin try/catch can't rescue this: an NSException from the setter aborts the process,
        // so the filter — never assigning an unavailable type — IS the guard.)
        if (supportedTypes.isEmpty()) return
        metadataOutput.metadataObjectTypes = supportedTypes
    }

    /**
     * Takes a picture through the engine, to a file in app-owned storage: Documents/CameraK, or the
     * configuration's output directory. PICTURES and DCIM no longer import it into Photos, which is
     * the media saver's job. The holder alone limits how many captures are in flight.
     */
    actual suspend fun takePictureToFile(): ImageCaptureResult = when (val result = engine.capture()) {
        is CameraKResult.Success -> ImageCaptureResult.SuccessWithFile(result.value.path)
        is CameraKResult.Failure -> ImageCaptureResult.Error(result.error.asException("Photo capture failed"))
    }

    actual fun toggleFlashMode() {
        setFlashMode(
            when (desired.value.flashMode) {
                FlashMode.OFF -> FlashMode.ON
                FlashMode.ON -> FlashMode.AUTO
                FlashMode.AUTO -> FlashMode.OFF
            },
        )
    }

    /**
     * Kept for the next start in any state; while Open, set through the engine. Before Open the mode is
     * also stored as the capture parameter, as 1.x did, which the engine's start sets again. A mode the
     * Open camera does not support is kept as OFF, as 1.x did.
     */
    actual fun setFlashMode(mode: FlashMode) {
        val kept = engine.facadeFlashMode(mode)
        updateDesired(flashMode = kept)
        engine.launchFacadeCommand(
            "setting the flash mode",
            whenNotOpen = { customCameraController.setFlashMode(kept.toAVCaptureFlashMode()) },
        ) { setFlashMode(kept) }
    }

    /** The flash the photo output will use while the camera is Open; the facade's desired flash otherwise. */
    actual fun getFlashMode(): FlashMode? = if (isOpen) engine.hardware.value.flashMode else desired.value.flashMode

    actual fun toggleTorchMode() {
        setTorchMode(
            when (desired.value.torchMode) {
                TorchMode.OFF -> TorchMode.ON
                TorchMode.ON -> TorchMode.AUTO
                TorchMode.AUTO -> TorchMode.OFF
            },
        )
    }

    /** Kept for the next start in any state; while Open, set through the engine. */
    actual fun setTorchMode(mode: TorchMode) {
        updateDesired(torchMode = mode)
        engine.launchFacadeCommand("setting the torch") { setTorchMode(mode) }
    }

    /** The torch as the device reports it while the camera is Open; the facade's desired torch otherwise. */
    actual fun getTorchMode(): TorchMode? = if (isOpen) engine.hardware.value.torchMode else desired.value.torchMode

    /**
     * Kept for the next start in any state, clamped to the camera in use as 1.x did (a fixed-zoom
     * camera keeps its only ratio); while Open, set through the engine, which clamps it on the session
     * queue.
     */
    actual fun setZoom(zoomRatio: Float) {
        val camera = customCameraController.currentDevice
        val kept = when {
            camera == null -> zoomRatio
            camera.maxAvailableVideoZoomFactor > camera.minVideoZoomFactor -> zoomRatio.coerceIn(
                camera.minVideoZoomFactor.toFloat(),
                camera.maxAvailableVideoZoomFactor.toFloat(),
            )
            else -> camera.minVideoZoomFactor.toFloat()
        }
        updateDesired(zoomRatio = kept)
        engine.launchFacadeCommand("setting the zoom") { setZoom(kept) }
    }

    /** The zoom the device reports while the camera is Open; the facade's desired zoom otherwise. */
    actual fun getZoom(): Float = if (isOpen) engine.zoom.value.ratio else desired.value.zoomRatio

    private val isOpen: Boolean get() = engine.state.value == EngineState.Open

    actual fun getMaxZoom(): Float = customCameraController.getMaxZoom()

    actual fun toggleCameraLens() {
        if (customCameraController.recordingInProgress()) {
            CameraKLogger.w("CameraK", "CameraK: cannot switch the lens while recording; stop the recording first")
            return
        }
        val previous = cameraLens
        val next = if (previous == CameraLens.BACK) CameraLens.FRONT else CameraLens.BACK
        cameraLens = next
        // Not started yet: the next startSession opens the new lens. A refused switch keeps the old one.
        switchThroughEngine(CameraSelection.ByLens(next, cameraDeviceType)) {
            if (cameraLens == next) cameraLens = previous
        }
    }

    /** Switches through the engine; [revert] undoes the facade's field when the switch was refused. */
    private fun switchThroughEngine(selection: CameraSelection, revert: () -> Unit) {
        engine.switchCamera(selection) { result ->
            val error = (result as? CameraKResult.Failure)?.error
            if (error != null && error != CameraKError.NotReady) {
                CameraKLogger.e("CameraK", "CameraK: switching the camera failed: $error", error.cause)
                revert()
            }
        }
    }

    actual fun getCameraLens(): CameraLens? = cameraLens

    actual fun getImageFormat(): ImageFormat = imageFormat

    actual fun getAspectRatio(): AspectRatio = aspectRatio

    actual fun getQualityPrioritization(): QualityPrioritization = qualityPriority

    actual fun getPreferredCameraDeviceType(): CameraDeviceType = cameraDeviceType

    actual fun setPreferredCameraDeviceType(deviceType: CameraDeviceType) {
        if (cameraDeviceType == deviceType) return // avoid a redundant AVCaptureSession input swap
        if (customCameraController.recordingInProgress()) {
            CameraKLogger.w("CameraK", "CameraK: cannot change the camera device type while recording")
            return
        }
        val previous = cameraDeviceType
        cameraDeviceType = deviceType
        // 1.x left the camera as it was for a type iOS has no discovery type for (MACRO).
        if (deviceType == CameraDeviceType.MACRO) return
        switchThroughEngine(CameraSelection.ByLens(cameraLens, deviceType)) {
            if (cameraDeviceType == deviceType) cameraDeviceType = previous
        }
    }

    actual fun getCameraCapabilities(): CameraCapabilities =
        CameraCapabilities(customCameraController.getCameraCapabilities())

    /**
     * Starts this facade's engine on its lens and desired hardware, and the engine's target; no view is
     * needed. Once the camera opens, the facade's flash is validated against it, as 1.x's setter did.
     */
    actual fun startSession() {
        val wanted = desired.value
        engine.launchStart(
            DesiredHardware(
                selection = CameraSelection.ByLens(cameraLens, cameraDeviceType),
                flashMode = wanted.flashMode,
                torchMode = wanted.torchMode,
                zoomRatio = wanted.zoomRatio,
                targetOrientation = engine.targetOrientation,
            ),
        ) { updateDesired(flashMode = engine.facadeFlashMode(desired.value.flashMode)) }
    }

    actual fun stopSession() {
        engine.launchStop()
    }

    actual fun addImageCaptureListener(listener: (ByteArray) -> Unit) {
        imageCaptureListeners.add(listener)
    }

    actual fun removeImageCaptureListener(listener: (ByteArray) -> Unit) {
        imageCaptureListeners.remove(listener)
    }

    /** Closes this facade's engine, which tears the session down once however often this runs. */
    actual fun cleanup() {
        engine.close()
    }

    /**
     * Starts a recording through the engine and returns its file path once AVFoundation reports it
     * started. The file is app-owned: the recording's output directory, the camera's, else
     * Documents/CameraK; PICTURES and DCIM no longer import it into Photos. A start while a recording
     * is active or starting, or one the engine refuses, throws IllegalStateException with the engine's
     * error as its cause chain.
     */
    actual suspend fun startRecording(configuration: VideoConfiguration): String =
        when (val started = engine.startRecording(configuration)) {
            is CameraKResult.Success -> started.value
            is CameraKResult.Failure -> throw IllegalStateException(
                "Starting the recording failed: ${started.error::class.simpleName}",
                started.error.cause,
            )
        }

    /**
     * Stops the recording and returns its result once AVFoundation has finished the file, or an error
     * once the engine's finish timeout passes. A recording that already ended by itself reports its
     * result. A full disk that kept a playable file reports that file, as 1.x did.
     */
    actual suspend fun stopRecording(): VideoCaptureResult = when (val stopped = engine.stopRecording()) {
        is CameraKResult.Success -> stopped.value.toVideoCaptureResult()
        is CameraKResult.Failure -> if (stopped.error is CameraKError.InvalidState) {
            engine.recorder.takeUnclaimedResult()?.toVideoCaptureResult()
                ?: VideoCaptureResult.Error(Exception("No active recording"))
        } else {
            stopped.toVideoCaptureResult()
        }
    }

    private fun CameraKResult<RecordedVideo>.toVideoCaptureResult(): VideoCaptureResult = when (this) {
        is CameraKResult.Success -> value.toVideoCaptureResult()
        is CameraKResult.Failure -> (error as? CameraKError.Storage)?.keptFilePath
            ?.let { VideoCaptureResult.Success(it, 0) }
            ?: VideoCaptureResult.Error(error.asException("Recording failed"))
    }

    private fun RecordedVideo.toVideoCaptureResult() = VideoCaptureResult.Success(path, duration.inWholeMilliseconds)

    /**
     * Pauses the recording; a no-op when nothing is recording. Below iOS 18 AVCaptureMovieFileOutput
     * cannot pause, so this throws UnsupportedOperationException rather than leave the camera and
     * microphone recording while the app shows "paused". It returns only once the movie output
     * reports the recording paused.
     */
    actual suspend fun pauseRecording() {
        val paused = engine.pauseRecording() as? CameraKResult.Failure ?: return
        when (val error = paused.error) {
            is CameraKError.InvalidState -> Unit
            is CameraKError.Unsupported -> throw UnsupportedOperationException(
                "Pausing a recording needs iOS 18 or later",
            )
            else -> throw IllegalStateException("The movie output did not pause the recording", error.cause)
        }
    }

    /**
     * Resumes a paused recording; a no-op when nothing is recording or the recording is not paused,
     * which below iOS 18 it never is.
     */
    actual suspend fun resumeRecording() {
        val resumed = engine.resumeRecording() as? CameraKResult.Failure ?: return
        when (val error = resumed.error) {
            is CameraKError.InvalidState, is CameraKError.Unsupported -> Unit
            else -> throw IllegalStateException("The movie output did not resume the recording", error.cause)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Device Orientation
    // ═══════════════════════════════════════════════════════════════

    /** How the device is held, as the engine observes it between start and stop. */
    actual fun getDeviceOrientation(): DeviceOrientation = engine.deviceOrientation.value

    /** [callback] hears each orientation the engine observes; null removes it. */
    actual fun setOnOrientationChangedListener(callback: ((DeviceOrientation) -> Unit)?) {
        engine.setDeviceOrientationListener(callback)
    }

    /**
     * Sets the engine's target orientation in any state, as 1.x did. The target decides how photos and
     * recordings are oriented. The preview shows what the interface shows, so it keeps following the
     * interface orientation.
     */
    actual fun setTargetOrientation(orientation: DeviceOrientation?) {
        engine.storeTargetOrientation(orientation)
    }

    /**
     * Turns the preview to the interface's orientation, so a portrait-locked app keeps an upright
     * portrait preview whichever way the device is held. Main thread only.
     */
    private fun applyPreviewOrientation() {
        interfaceOrientation()?.let(customCameraController::orientPreview)
    }

    /**
     * The interface's current orientation as a video orientation, as the preview's host last
     * reported it; null before it has a window scene. Test seam: tests answer for a window the
     * simulator does not give them.
     */
    internal var interfaceOrientation: () -> AVCaptureVideoOrientation? = { hostInterfaceOrientation }

    /** The orientation photos and movies take: the engine's target when set, otherwise the device's. */
    internal fun effectiveVideoOrientation(): AVCaptureVideoOrientation = engine.captureVideoOrientation()
}

/**
 * A 1.x exception for [this] error: [what] failed, with the error's kind and, for a platform error,
 * its domain and code in the message, and the platform's throwable as the cause.
 */
internal fun CameraKError.asException(what: String): Exception {
    val platform = (cause as? NSErrorException)?.error?.summary?.let { " ($it)" }.orEmpty()
    return Exception("$what: ${this::class.simpleName}$platform", cause)
}

/**
 * Writes [data] to the file at [filePath], atomically; a failure carries the file system's NSError
 * as its cause, and its message names neither the file nor its path.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun writeDataToFile(data: NSData, filePath: String): Result<Unit> = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    if (data.writeToFile(filePath, NSDataWritingAtomic, error.ptr)) {
        Result.success(Unit)
    } else {
        val cause = error.value
        Result.failure(
            IllegalStateException(
                "Could not write the file${cause?.let { ": ${it.summary}" }.orEmpty()}",
                cause?.let(::NSErrorException),
            ),
        )
    }
}

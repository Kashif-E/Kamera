@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import platform.AVFoundation.*
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private const val TAG = "CameraK"

/**
 * The engine's AVCaptureSession, its serial queue and its camera inputs and outputs (design §2.4). It
 * delegates to its collaborators, each of which owns its own state:
 *
 * - [SessionQueue]: the one serial queue every session call runs on;
 * - [SessionLifecycle]: the session, its transactions, the changes plugins queue, start, stop and teardown;
 * - [CameraInputs]: the cameras, the one in use and the lens and device-type switches;
 * - [StillCapture]: the photo output, the flash mode captures request and the captures;
 * - [CameraControls]: the torch and zoom of the camera in use;
 * - [PreviewLayers]: the preview layer, its mirroring and its orientation.
 */
internal class CustomCameraController private constructor(
    val qualityPrioritization: QualityPrioritization,
    private val initialCameraLens: CameraLens,
    internal val aspectRatio: AspectRatio,
    private val targetResolution: Pair<Int, Int>?,
    private val mirrorFrontCamera: Boolean,
    seams: ControllerSeams,
) : NSObject(),
    AVCapturePhotoCaptureDelegateProtocol {
    constructor(
        qualityPrioritization: QualityPrioritization,
        initialCameraLens: CameraLens = CameraLens.BACK,
        aspectRatio: AspectRatio = AspectRatio.RATIO_4_3,
        targetResolution: Pair<Int, Int>? = null,
        mirrorFrontCamera: Boolean = false,
    ) : this(
        qualityPrioritization,
        initialCameraLens,
        aspectRatio,
        targetResolution,
        mirrorFrontCamera,
        ControllerSeams(),
    )

    /**
     * Test seam: [sessionFactory] supplies the session, and [inputConfigurator], when set, adds the
     * initial video input in place of AVCaptureDevice discovery (the simulator has no camera).
     * [lensSwitcher], when set, adds the input for the lens switchCamera moves to (true for front)
     * in place of the device lookup, and [previewMirroring], when set, applies the preview layer's
     * mirroring in place of setting it on the layer's connection. [cameraDevices], when set, finds
     * the cameras setup and the lens switches open, in place of AVCaptureDevice discovery.
     * [photoCapturer], when set, receives each capture request in place of the photo output.
     * [previewOrienting], when set, applies the preview's orientation in place of setting it on the
     * preview layer's connection, and [previewRotating] the rotation coordinator's preview angle.
     * [previewPointConverting], when set, converts a preview-layer point to a point of interest.
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
        cameraDevices: CameraDeviceProvider? = null,
        photoCapturer: PhotoCapturer? = null,
        previewOrienting: ((AVCaptureVideoPreviewLayer?, AVCaptureVideoOrientation) -> Unit)? = null,
        previewRotating: ((AVCaptureVideoPreviewLayer, Double) -> Unit)? = null,
        previewPointConverting: PreviewPointConverter? = null,
    ) : this(
        qualityPrioritization,
        initialCameraLens,
        aspectRatio,
        targetResolution,
        mirrorFrontCamera,
        ControllerSeams(
            sessionFactory = sessionFactory,
            inputConfigurator = inputConfigurator,
            lensSwitcher = lensSwitcher,
            previewMirroring = previewMirroring,
            cameraDevices = cameraDevices,
            photoCapturer = photoCapturer,
            previewOrienting = previewOrienting,
            previewRotating = previewRotating,
            previewPointConverting = previewPointConverting,
        ),
    )

    /** Every AVCaptureSession call runs on this serial queue. */
    private val sessionQueue: SessionQueue = SessionQueue()

    /** The session: its setup transactions, the changes plugins queue, start, stop and teardown. */
    private val lifecycle: SessionLifecycle = SessionLifecycle(
        queue = sessionQueue,
        sessionFactory = seams.sessionFactory,
        aspectRatio = aspectRatio,
        targetResolution = targetResolution,
        onStarted = {
            // A torch set before the session ran does not survive its start.
            controls.applyConfiguredTorch()
            stills.prepareOnQueue()
        },
        disableDeferredStart = { stills.disableDeferredStartIfAsked(it) },
        reportError = ::reportError,
    )

    /** The preview layer, its mirroring and its orientation. */
    private val preview: PreviewLayers =
        PreviewLayers(
            sessionQueue,
            seams.previewMirroring,
            seams.previewOrienting,
            seams.previewRotating,
            seams.previewPointConverting,
        )

    /** The torch and zoom of the camera in use. */
    private val controls: CameraControls = CameraControls(sessionQueue, { inputs.currentCamera }, ::reportError)

    /** The photo output, the flash mode captures request and the captures themselves. */
    private val stills: StillCapture = StillCapture(
        qualityPrioritization = qualityPrioritization,
        mirrorFrontCamera = mirrorFrontCamera,
        photoCapturer = seams.photoCapturer,
        queue = sessionQueue,
        session = { lifecycle.session },
        currentCamera = { inputs.currentCamera },
        isUsingFrontCamera = { inputs.isUsingFrontCamera },
        currentVideoOrientation = ::currentVideoOrientation,
        reportError = ::reportError,
        targetResolution = targetResolution,
        stillPreset = { lifecycle.stillPreset },
    )

    /** The cameras, the one in use and the session's camera input. */
    private val inputs: CameraInputs = CameraInputs(
        queue = sessionQueue,
        session = { lifecycle.session },
        inputConfigurator = seams.inputConfigurator,
        lensSwitcher = seams.lensSwitcher,
        cameraDevices = seams.cameraDevices,
        controls = controls,
        stills = stills,
        preview = preview,
        reportError = ::reportError,
    )

    var captureSession: AVCaptureSession?
        get() = lifecycle.session?.avSession

        @Deprecated(
            "No replacement: setupSession() creates and owns the session on the controller's session queue. " +
                "Assigning one from outside blocks the caller until that queue is free.",
        )
        set(value) {
            val replace = { lifecycle.replaceSession(value?.let(::AVCaptureSessionOps)) }
            // Synchronous so the next read returns the new session; already on the queue, waiting on
            // it would deadlock.
            if (sessionQueue.isCurrent) replace() else sessionQueue.sync(replace)
        }

    /** Whether setupSession has created the session (and cleanupSession not yet released it). */
    internal val hasSession: Boolean
        get() = lifecycle.session != null

    /** A UIKit layer: read and written on main only. */
    var cameraPreviewLayer: AVCaptureVideoPreviewLayer? by preview::layer

    /** The orientation photos are captured in; the engine answers with its target or the device's. */
    internal var captureOrientation: () -> AVCaptureVideoOrientation by stills::captureOrientation

    /** How the photo connection is turned for a capture; the engine answers. */
    internal var captureRotation: () -> VideoRotation by stills::captureRotation

    /** Whether a recording is in progress; the engine answers from its recorder. */
    internal var recordingInProgress: () -> Boolean by inputs::recordingInProgress

    /** The format captures are taken in: HEIC asks the photo output for HEVC where it can encode it. */
    internal var photoFormat: ImageFormat by stills::photoFormat

    /** Whether outputs get their deferred start turned off (iOS 26 and later); the engine sets it. */
    internal var turnsDeferredStartOff: Boolean by stills::turnsDeferredStartOff

    var onPhotoCapture: ((NSData?) -> Unit)? = null
    var onError: ((CameraException) -> Unit)? = null
    var onSessionReady: (() -> Unit)? = null

    /** The camera in use: the one the session's video input captures from. */
    internal val currentDevice: CaptureDeviceOps? get() = inputs.currentCamera

    /** The flash mode a capture's settings carry now: [flashMode], or off when the photo output lacks it. */
    internal val captureFlashMode: AVCaptureFlashMode get() = stills.captureFlashMode

    /** The flash mode captures request; a mode the photo output lacks is turned off at capture time. */
    var flashMode: AVCaptureFlashMode by stills::flashMode

    /** The torch mode applied to the camera in use whenever the session starts or the lens changes. */
    var torchMode: AVCaptureTorchMode by controls::torchMode

    /**
     * A camera failure. [message] says what CameraK was doing; [cause], when the platform reported
     * one, carries its error (an NSError's domain, code and description).
     */
    sealed class CameraException(message: String, cause: Throwable?) : Exception(message, cause) {
        class DeviceNotAvailable(message: String = "No camera is available", cause: Throwable? = null) :
            CameraException(message, cause)

        class ConfigurationError(message: String, cause: Throwable? = null) : CameraException(message, cause)

        class CaptureError(message: String, cause: Throwable? = null) : CameraException(message, cause)
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
        val generation = lifecycle.setupGeneration
        // Off the main thread to avoid UI stalls (#73), and on the session queue so nothing else
        // touches the session while it is being built.
        sessionQueue.async {
            if (lifecycle.setupGeneration != generation) return@async
            val failure = configureOnQueue(device = null, lens = initialCameraLens, cameraDeviceType = cameraDeviceType)
            if (failure != null) {
                reportError(failure)
                return@async
            }
            val ops = lifecycle.session
            dispatch_async(dispatch_get_main_queue()) {
                if (lifecycle.setupGeneration == generation && lifecycle.session === ops) {
                    onSessionReady?.invoke()
                }
            }
        }
    }

    /** Makes the session now, unconfigured, so a preview layer shows it before the camera starts. */
    internal fun prepareSession(): CaptureSessionOps = lifecycle.prepareSession()

    /** The session: configured, or prepared and waiting for its first configuration; null before either. */
    internal val sessionOps: CaptureSessionOps? get() = lifecycle.sessionOps

    /**
     * Configures the session in one transaction: the camera input ([device], or the one [lens] and
     * [cameraDeviceType] find), the photo output, the preset and every change queued so far. Session
     * queue only. Every failure comes back as the returned exception and none is thrown across the
     * queue: setup used to throw inside its dispatch_async, which aborts the process.
     */
    internal fun configureOnQueue(
        device: CaptureDeviceOps?,
        lens: CameraLens,
        cameraDeviceType: CameraDeviceType,
    ): CameraException? {
        // Published before configuring so changes applied below (safeAddOutput) reach it.
        val ops = lifecycle.beginSetup()

        // One transaction: input, photo output, the final preset and every change queued so far.
        ops.beginConfiguration()
        val failure: CameraException? = try {
            // A photo session never reconfigures the app's audio session; only an audio recording does.
            ops.setAutomaticallyConfiguresApplicationAudioSession(false)
            if (inputs.setUp(ops, lens, cameraDeviceType, device)) {
                stills.setUpOutput(ops)
                lifecycle.applySessionPreset(ops)
                lifecycle.runPendingConfigurations()
                null
            } else {
                CameraException.DeviceNotAvailable()
            }
        } catch (e: CameraException) {
            CameraKLogger.e(TAG, "session setup failed", e)
            e
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "session setup failed", e)
            CameraException.ConfigurationError("Session setup failed", e)
        } finally {
            ops.commitConfiguration()
        }

        if (failure != null) {
            lifecycle.discardSession()
            stills.discardOutput()
            return failure
        }
        stills.applyMaxPhotoDimensions()
        return null
    }

    private fun reportError(error: CameraException) {
        dispatch_async(dispatch_get_main_queue()) {
            onError?.invoke(error)
        }
    }

    /** The preset stills run at, which a recording's preset gives way to. Session queue only. */
    internal val stillPreset: String? get() = lifecycle.stillPreset

    /** The flash modes the photo output supports with the camera now in use; none before setup. */
    internal val captureFlashModes: List<AVCaptureFlashMode> get() = stills.captureFlashModes

    /** The codecs the photo output can encode a photo in with the camera now in use; none before setup. */
    internal val availablePhotoCodecs: List<String> get() = stills.availablePhotoCodecs

    /** Whether a HEIC capture can be taken: the photo output can encode HEVC with the camera now in use. */
    internal val canCaptureHeic: Boolean get() = stills.canCaptureHeic

    /** Every built-in lens (wide, telephoto, ultra-wide) on both positions. */
    fun getCameraCapabilities(): List<LensInfo> = inputs.lenses()

    /** Queues a configuration change for the session queue (Apple WWDC pattern); callable from any thread. */
    fun queueConfigurationChange(change: () -> Unit) = lifecycle.queueConfigurationChange(change)

    /** Adds [output] to the session: at once inside a configuration change, otherwise as one. */
    fun safeAddOutput(output: AVCaptureOutput) = lifecycle.safeAddOutput(output)

    /** Whether [output] is attached to the session. Call it on the session queue (inside a change). */
    internal fun containsOutput(output: AVCaptureOutput): Boolean = lifecycle.containsOutput(output)

    /** Removes an output [safeAddOutput] added, as a configuration change; plugins call it on detach. */
    fun safeRemoveOutput(output: AVCaptureOutput) = lifecycle.safeRemoveOutput(output)

    /** Adds the input [create] makes unless one satisfying [isPresent] is attached; a failure goes to [onError]. */
    internal fun addInputUnlessPresent(
        what: String,
        isPresent: (AVCaptureInput) -> Boolean,
        create: () -> Result<AVCaptureInput>,
    ) = lifecycle.addInputUnlessPresent(what, isPresent, create)

    /** Session queue, before setup: [change] runs inside the setup transaction. */
    internal fun joinSetupOnQueue(change: () -> Unit) = lifecycle.joinSetupOnQueue(change)

    /** The camera's input in the session now. Session queue. */
    internal val currentVideoInput: AVCaptureInput? get() = inputs.videoInput

    /** Runs [block] on the session queue after every piece of session work already requested. */
    internal fun afterQueuedSessionWork(block: () -> Unit) {
        sessionQueue.async(block)
    }

    /** Runs [block] on the session queue once [delayMs] have passed. */
    internal fun onSessionQueueAfter(delayMs: Long, block: () -> Unit) {
        sessionQueue.after(delayMs, block)
    }

    /** Adds a recording's movie output and microphone and sets its preset in one transaction. Session queue only. */
    internal fun configureMovieOnQueue(
        output: AVCaptureMovieFileOutput,
        microphone: (() -> Result<AVCaptureInput>)?,
        preset: String?,
    ): Result<AVCaptureInput?> = lifecycle.configureMovieOnQueue(output, microphone, preset)

    /** Sets [preset], or the still preset when it is null, in one transaction. Session queue only. */
    internal fun setSessionPresetOnQueue(preset: String?) = lifecycle.setSessionPresetOnQueue(preset)

    /** Removes a finished recording's output and microphone and restores the still preset. Session queue only. */
    internal fun releaseMovieOnQueue(output: AVCaptureMovieFileOutput, microphone: AVCaptureInput?) =
        lifecycle.releaseMovieOnQueue(output, microphone)

    fun startSession() {
        sessionQueue.async { lifecycle.startRunningOnQueue() }
    }

    fun stopSession() {
        sessionQueue.async { lifecycle.stopRunningOnQueue() }
    }

    /** Starts the configured session unless it runs, then applies the torch and prepares the photo output. */
    internal fun startRunningOnQueue() = lifecycle.startRunningOnQueue()

    /** Stops the session if it runs or the system holds it interrupted. Session queue only. */
    internal fun stopRunningOnQueue() = lifecycle.stopRunningOnQueue()

    /** Whether setup has configured the session (and cleanupSession not yet released it). */
    internal val isConfigured: Boolean get() = lifecycle.isConfigured

    /** The camera facing [lens] of [type], else the one facing the other way, else any camera of that type. */
    internal fun findCamera(lens: CameraLens, type: CameraDeviceType): CaptureDeviceOps? = inputs.findCamera(lens, type)

    internal fun findCameraById(id: String): CaptureDeviceOps? = inputs.findCameraById(id)

    /** Moves the session to [camera]'s input in one transaction. Session queue only; returns why it failed. */
    internal fun useDeviceOnQueue(camera: CaptureDeviceOps): CameraException? = inputs.useDeviceOnQueue(camera)

    /**
     * Shows [layer] as the preview: bound to the session on the session queue, mirrored for the front
     * camera, removed with the session. Main only.
     */
    internal fun adoptPreviewLayer(layer: AVCaptureVideoPreviewLayer) =
        preview.adopt(layer, lifecycle.sessionOps?.avSession) { inputs.isUsingFrontCamera }

    /** Ends [layer] once nothing shows it: the session is taken out of it on the session queue. Main only. */
    internal fun releasePreviewLayer(layer: AVCaptureVideoPreviewLayer) = preview.release(layer)

    /** Main only: the point of interest under content point ([x], [y]) of the preview layer; null without one. */
    internal fun previewDevicePoint(x: Float, y: Float): PointOfInterest? = preview.devicePoint(x, y)

    /**
     * Stops and releases the session on the session queue, after any setup already queued, so a setup
     * still in flight cannot leave a running session behind, and after main has taken the session out
     * of the preview layer. Callable from any thread; [then] runs on the session queue once the session
     * is released.
     */
    fun cleanupSession(then: () -> Unit = {}) {
        lifecycle.invalidateSetups()
        preview.remove {
            sessionQueue.async {
                lifecycle.tearDownOnQueue()
                stills.releaseOnQueue()
                inputs.releaseOnQueue()
                controls.forgetRequestedZoom()
                then()
            }
        }
    }

    fun setupPreviewLayer(view: UIView) {
        val session = lifecycle.session?.avSession ?: return
        preview.setUp(view, session)
    }

    /** Sets the preview's orientation. Call it on main: the preview layer belongs to UIKit. */
    internal fun orientPreview(orientation: AVCaptureVideoOrientation) = preview.orient(orientation)

    /** Turns [layer] by [rotation]. Call it on main: the preview layer belongs to UIKit. */
    internal fun turnPreview(layer: AVCaptureVideoPreviewLayer, rotation: VideoRotation) = preview.turn(layer, rotation)

    fun currentVideoOrientation(): AVCaptureVideoOrientation = preview.currentVideoOrientation()

    /** Sets the flash mode later captures request; a mode the device lacks is turned off at capture time. */
    fun setFlashMode(mode: AVCaptureFlashMode) = stills.setFlashMode(mode)

    fun setTorchMode(mode: AVCaptureTorchMode) = controls.setTorchMode(mode)

    /** Applies [torchMode] to the camera in use unless the camera already reports it. Session queue only. */
    internal fun reapplyTorchOnQueue() = controls.applyConfiguredTorch()

    /** Sets [mode] on [camera] under its configuration lock. Session queue only; returns why it could not lock. */
    internal fun setTorchOnQueue(camera: CaptureDeviceOps, mode: AVCaptureTorchMode): CameraException? =
        controls.setTorchOnQueue(camera, mode)

    /** Sets [factor] on [camera] under its configuration lock. Session queue only; returns why it could not lock. */
    internal fun setZoomOnQueue(camera: CaptureDeviceOps, factor: Double): CameraException? =
        controls.setZoomOnQueue(camera, factor)

    /** Sets the zoom level smoothly, clamped to [1, getMaxZoom()]. */
    fun setZoom(zoomFactor: Float) = controls.setZoom(zoomFactor)

    /** The current zoom factor (1.0 = no zoom). */
    fun getZoom(): Float = controls.getZoom()

    /** The largest zoom factor of the current camera. */
    fun getMaxZoom(): Float = controls.getMaxZoom()

    /**
     * Capture an image. Output quality is governed by the configured [qualityPrioritization].
     * The photo goes to [onPhotoCapture], a failure to [onError].
     */
    fun captureImage() {
        sessionQueue.async {
            val issued = capturePhotoOnQueue(
                object : PhotoCaptureCallbacks {
                    override fun photoProcessed(data: NSData?, error: NSError?) = deliverPhoto(data, error)

                    override fun captureFinished(error: NSError?) = Unit
                },
            )
            if (!issued) reportError(CameraException.ConfigurationError("Camera not ready for capture"))
        }
    }

    /** Issues one capture whose callbacks go only to [callbacks]. Session queue only; false when not ready. */
    internal fun capturePhotoOnQueue(callbacks: PhotoCaptureCallbacks): Boolean = stills.captureOnQueue(callbacks)

    /** Switches to another camera device type facing the same way. */
    fun switchToDeviceType(deviceType: CameraDeviceType) = inputs.switchToDeviceType(deviceType)

    fun switchCamera() = inputs.switchCamera()

    override fun captureOutput(
        output: AVCapturePhotoOutput,
        didFinishProcessingPhoto: AVCapturePhoto,
        error: NSError?,
    ) {
        deliverPhoto(if (error == null) didFinishProcessingPhoto.fileDataRepresentation() else null, error)
    }

    /** Capture delegates kept alive until AVFoundation's last callback for their capture. */
    internal val outstandingPhotoDelegates: Int get() = stills.outstandingDelegates

    private fun deliverPhoto(data: NSData?, error: NSError?) {
        if (error != null) {
            onError?.invoke(
                CameraException.CaptureError("Photo capture failed: ${error.summary}", NSErrorException(error)),
            )
            return
        }
        onPhotoCapture?.invoke(data)
    }
}

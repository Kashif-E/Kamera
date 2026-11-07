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
import kotlinx.atomicfu.atomic
import platform.AVFoundation.*
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.Volatile

private const val TAG = "CameraK"

/**
 * Convert CameraDeviceType enum to AVFoundation device type string
 */
internal fun CameraDeviceType.toAVCaptureDeviceType(): String? = when (this) {
    CameraDeviceType.WIDE_ANGLE -> AVCaptureDeviceTypeBuiltInWideAngleCamera
    CameraDeviceType.TELEPHOTO -> AVCaptureDeviceTypeBuiltInTelephotoCamera
    CameraDeviceType.ULTRA_WIDE -> AVCaptureDeviceTypeBuiltInUltraWideCamera
    CameraDeviceType.MACRO -> null // Macro camera would need iOS 15+ check
    CameraDeviceType.DEFAULT -> AVCaptureDeviceTypeBuiltInWideAngleCamera
}

internal class CustomCameraController private constructor(
    val qualityPrioritization: QualityPrioritization,
    private val initialCameraLens: CameraLens,
    private val aspectRatio: AspectRatio,
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
        ),
    )

    private val sessionFactory = seams.sessionFactory

    /** Every AVCaptureSession call runs on this serial queue. */
    private val sessionQueue: SessionQueue = SessionQueue()

    /** The preview layer, its mirroring and its orientation. */
    private val preview: PreviewLayers =
        PreviewLayers(seams.previewMirroring, seams.previewOrienting, seams.previewRotating)

    /** The torch and zoom of the camera in use. */
    private val controls: CameraControls = CameraControls(sessionQueue, { inputs.currentCamera }, ::reportError)

    /** The photo output, the flash mode captures request and the captures themselves. */
    private val stills: StillCapture = StillCapture(
        qualityPrioritization = qualityPrioritization,
        mirrorFrontCamera = mirrorFrontCamera,
        photoCapturer = seams.photoCapturer,
        queue = sessionQueue,
        session = { session },
        currentCamera = { inputs.currentCamera },
        isUsingFrontCamera = { inputs.isUsingFrontCamera },
        currentVideoOrientation = ::currentVideoOrientation,
        reportError = ::reportError,
    )

    /** The cameras, the one in use and the session's camera input. */
    private val inputs: CameraInputs = CameraInputs(
        queue = sessionQueue,
        session = { session },
        inputConfigurator = seams.inputConfigurator,
        lensSwitcher = seams.lensSwitcher,
        cameraDevices = seams.cameraDevices,
        controls = controls,
        stills = stills,
        preview = preview,
        reportError = ::reportError,
    )

    /** Bumped by [cleanupSession] so a setup requested before it never reports ready. */
    private val setupGeneration = atomic(0)

    /** Written only on [sessionQueue]; read elsewhere only to check presence. */
    @Volatile
    private var session: CaptureSessionOps? = null

    /**
     * The session [prepareSession] made ahead of setup, so a preview layer can show it before the
     * camera starts; setup configures this one instead of asking [sessionFactory] for another.
     */
    @Volatile
    private var preparedSession: CaptureSessionOps? = null

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
            if (sessionQueue.isCurrent) replace() else sessionQueue.sync(replace)
        }

    /** Whether setupSession has created the session (and cleanupSession not yet released it). */
    internal val hasSession: Boolean
        get() = session != null

    /** A UIKit layer: read and written on main only. */
    var cameraPreviewLayer: AVCaptureVideoPreviewLayer?
        get() = preview.layer
        set(value) {
            preview.layer = value
        }

    /**
     * The orientation photos are captured in; AppleCameraEngine answers with its target orientation
     * when one is set, otherwise the device's.
     */
    internal var captureOrientation: () -> AVCaptureVideoOrientation
        get() = stills.captureOrientation
        set(value) {
            stills.captureOrientation = value
        }

    /**
     * How the photo connection is turned for a capture: the engine answers with the coordinator's angle
     * from iOS 17 when no target orientation is set, otherwise with [captureOrientation].
     */
    internal var captureRotation: () -> VideoRotation
        get() = stills.captureRotation
        set(value) {
            stills.captureRotation = value
        }

    /** Whether a recording is in progress; the engine answers from its recorder. */
    internal var recordingInProgress: () -> Boolean
        get() = inputs.recordingInProgress
        set(value) {
            inputs.recordingInProgress = value
        }

    /** The format captures are taken in: HEIC asks the photo output for HEVC where it can encode it. */
    internal var photoFormat: ImageFormat
        get() = stills.photoFormat
        set(value) {
            stills.photoFormat = value
        }

    /** Whether outputs get their deferred start turned off (iOS 26 and later); the engine sets it. */
    internal var turnsDeferredStartOff: Boolean
        get() = stills.turnsDeferredStartOff
        set(value) {
            stills.turnsDeferredStartOff = value
        }

    var onPhotoCapture: ((NSData?) -> Unit)? = null
    var onError: ((CameraException) -> Unit)? = null
    var onSessionReady: (() -> Unit)? = null

    /** The camera in use: the one the session's video input captures from. */
    internal val currentDevice: CaptureDeviceOps? get() = inputs.currentCamera

    /** The flash mode a capture's settings carry now: [flashMode], or off when the photo output lacks it. */
    internal val captureFlashMode: AVCaptureFlashMode get() = stills.captureFlashMode

    /** The flash mode captures request; a mode the photo output lacks is turned off at capture time. */
    var flashMode: AVCaptureFlashMode
        get() = stills.flashMode
        set(value) {
            stills.flashMode = value
        }

    /** The torch mode applied to the camera in use whenever the session starts or the lens changes. */
    var torchMode: AVCaptureTorchMode
        get() = controls.torchMode
        set(value) {
            controls.torchMode = value
        }

    // Configuration changes for plugin outputs (Apple WWDC pattern). Touched only on sessionQueue.
    private val pendingConfigurations = mutableListOf<() -> Unit>()

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
        val generation = setupGeneration.value
        // Off the main thread to avoid UI stalls (#73), and on the session queue so nothing else
        // touches the session while it is being built.
        sessionQueue.async {
            if (setupGeneration.value != generation) return@async
            val failure = configureOnQueue(device = null, lens = initialCameraLens, cameraDeviceType = cameraDeviceType)
            if (failure != null) {
                reportError(failure)
                return@async
            }
            val ops = session
            dispatch_async(dispatch_get_main_queue()) {
                if (setupGeneration.value == generation && session === ops) {
                    onSessionReady?.invoke()
                }
            }
        }
    }

    /**
     * Makes the session now, on the calling thread, without configuring it: the engine calls it once
     * from its constructor, so a preview layer is bound to the session before the camera starts.
     */
    internal fun prepareSession(): CaptureSessionOps = sessionOps ?: sessionFactory().also { preparedSession = it }

    /** The session: configured, or prepared and waiting for its first configuration; null before either. */
    internal val sessionOps: CaptureSessionOps? get() = session ?: preparedSession

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
        val ops = preparedSession ?: sessionFactory()
        // Published before configuring so changes applied below (safeAddOutput) reach it.
        session = ops

        // One transaction: input, photo output, the final preset and every change queued so far.
        ops.beginConfiguration()
        val failure: CameraException? = try {
            // A photo session never reconfigures the app's audio session; only an audio recording does.
            ops.setAutomaticallyConfiguresApplicationAudioSession(false)
            if (inputs.setUp(ops, lens, cameraDeviceType, device)) {
                stills.setUpOutput(ops)
                applySessionPreset(ops)
                runPendingConfigurations()
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
            session = null
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

    /**
     * The preset stills run at: the one setup chose ([presetCandidates]), which a recording's preset
     * gives way to and returns to afterwards. Session queue only; null before setup.
     */
    internal var stillPreset: String? = null
        private set

    /**
     * Sets the best preset the session can take with its current input. Assigning one it cannot
     * take raises an NSException Kotlin cannot catch, so each is checked first. Runs on the session
     * queue inside the setup transaction.
     */
    private fun applySessionPreset(ops: CaptureSessionOps) {
        val candidates = presetCandidates(aspectRatio, targetResolution)
        val preset = candidates.firstOrNull(ops::canSetSessionPreset)
        if (preset == null) {
            CameraKLogger.w(TAG, "the session can take none of the presets $candidates; keeping its default")
            return
        }
        if (preset != candidates.first()) {
            CameraKLogger.w(TAG, "the session cannot take preset ${candidates.first()}; using $preset")
        }
        ops.setSessionPreset(preset)
        stillPreset = preset
    }

    /** The flash modes the photo output supports with the camera now in use; none before setup. */
    internal val captureFlashModes: List<AVCaptureFlashMode> get() = stills.captureFlashModes

    /** The codecs the photo output can encode a photo in with the camera now in use; none before setup. */
    internal val availablePhotoCodecs: List<String> get() = stills.availablePhotoCodecs

    /** Whether a HEIC capture can be taken: the photo output can encode HEVC with the camera now in use. */
    internal val canCaptureHeic: Boolean get() = stills.canCaptureHeic

    /**
     * Enumerates every built-in lens (wide, telephoto, ultra-wide) on both positions.
     */
    fun getCameraCapabilities(): List<LensInfo> = inputs.lenses()

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
        sessionQueue.async {
            pendingConfigurations.add(change)
            val ops = session ?: return@async
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
                CameraKLogger.e(TAG, "Error processing configuration change", e)
            }
        }
    }

    /**
     * Safely adds an output to the capture session.
     * Inside a queueConfigurationChange block it adds the output at once; from any other thread it
     * queues the addition as a configuration change.
     */
    fun safeAddOutput(output: AVCaptureOutput) {
        if (!sessionQueue.isCurrent) {
            queueConfigurationChange { safeAddOutput(output) }
            return
        }
        val session = session ?: return
        if (session.canAddOutput(output)) {
            session.addOutput(output)
        } else {
            CameraKLogger.w(TAG, "the capture session refused an output; it was not added")
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
     * An input [create] cannot make, or one the session refuses, is logged and reported to [onError]
     * as a ConfigurationError naming [what], with the platform's error as its cause; the session
     * carries on without it.
     */
    internal fun addInputUnlessPresent(
        what: String,
        isPresent: (AVCaptureInput) -> Boolean,
        create: () -> Result<AVCaptureInput>,
    ) {
        queueConfigurationChange {
            val ops = session ?: return@queueConfigurationChange
            if (ops.inputs.any(isPresent)) return@queueConfigurationChange
            val failure = create().fold(
                onSuccess = { input ->
                    if (ops.canAddInput(input)) {
                        ops.addInput(input)
                        null
                    } else {
                        CameraException.ConfigurationError("The capture session refused $what")
                    }
                },
                onFailure = { cause ->
                    val reported = (cause as? NSErrorException)?.error?.let { ": ${it.summary}" }.orEmpty()
                    CameraException.ConfigurationError("Could not add $what$reported", cause)
                },
            ) ?: return@queueConfigurationChange
            CameraKLogger.e(TAG, "adding $what failed", failure)
            reportError(failure)
        }
    }

    /**
     * Session queue, before the session is configured: [change] runs inside the setup transaction, as
     * a change queued before setup does.
     */
    internal fun joinSetupOnQueue(change: () -> Unit) {
        pendingConfigurations.add(change)
    }

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

    /**
     * Adds a recording's movie [output], and the microphone [microphone] makes when it is given, and
     * sets [preset] (the recording quality's) in one configuration transaction (design §5). The session
     * configures the app's audio session only for an audio recording. A microphone that cannot be made
     * or that the session refuses is logged and reported to [onError] as a ConfigurationError naming it,
     * with the platform's error as the cause, and the recording goes ahead without audio. Session queue
     * only; returns the microphone input added (null without one), or why the output could not be added.
     */
    internal fun configureMovieOnQueue(
        output: AVCaptureMovieFileOutput,
        microphone: (() -> Result<AVCaptureInput>)?,
        preset: String?,
    ): Result<AVCaptureInput?> {
        val ops = session ?: return Result.failure(CameraException.DeviceNotAvailable("The session is not set up"))
        ops.beginConfiguration()
        try {
            ops.setAutomaticallyConfiguresApplicationAudioSession(microphone != null)
            if (!ops.canAddOutput(output)) {
                return Result.failure(
                    CameraException.ConfigurationError("The capture session refused the movie output"),
                )
            }
            ops.addOutput(output)
            stills.disableDeferredStartIfAsked(output)
            val audio = microphone?.let { addMicrophone(ops, it) }
            when {
                preset == null -> Unit
                ops.canSetSessionPreset(preset) -> ops.setSessionPreset(preset)
                else -> CameraKLogger.w(TAG, "the session cannot take the recording's preset $preset")
            }
            return Result.success(audio)
        } finally {
            ops.commitConfiguration()
        }
    }

    private fun addMicrophone(ops: CaptureSessionOps, microphone: () -> Result<AVCaptureInput>): AVCaptureInput? {
        val failure = microphone().fold(
            onSuccess = { input ->
                if (ops.canAddInput(input)) {
                    ops.addInput(input)
                    return input
                }
                CameraException.ConfigurationError("The capture session refused the microphone")
            },
            onFailure = { cause ->
                val reported = (cause as? NSErrorException)?.error?.let { ": ${it.summary}" }.orEmpty()
                CameraException.ConfigurationError("Could not add the microphone$reported", cause)
            },
        )
        CameraKLogger.e(TAG, "adding the microphone failed", failure)
        reportError(failure)
        return null
    }

    /**
     * Sets [preset], a recording quality's, or the still preset when it is null, in one configuration
     * transaction: a prepared movie output's recordings (w6 decision G2). Session queue only.
     */
    internal fun setSessionPresetOnQueue(preset: String?) {
        val ops = session ?: return
        val wanted = preset ?: stillPreset ?: return
        ops.beginConfiguration()
        try {
            if (ops.canSetSessionPreset(wanted)) {
                ops.setSessionPreset(wanted)
            } else {
                CameraKLogger.w(TAG, "the session cannot take preset $wanted")
            }
        } finally {
            ops.commitConfiguration()
        }
    }

    /**
     * Removes a finished recording's [output] and [microphone] and returns the session to the still
     * preset, in one transaction, so the session and the microphone indicator are photo-only again.
     * Session queue only.
     */
    internal fun releaseMovieOnQueue(output: AVCaptureMovieFileOutput, microphone: AVCaptureInput?) {
        val ops = session ?: return
        ops.beginConfiguration()
        try {
            if (output in ops.outputs) ops.removeOutput(output)
            if (microphone != null && microphone in ops.inputs) ops.removeInput(microphone)
            stillPreset?.let { if (ops.canSetSessionPreset(it)) ops.setSessionPreset(it) }
            ops.setAutomaticallyConfiguresApplicationAudioSession(false)
        } finally {
            ops.commitConfiguration()
        }
    }

    fun startSession() {
        sessionQueue.async { startRunningOnQueue() }
    }

    fun stopSession() {
        sessionQueue.async { stopRunningOnQueue() }
    }

    /** Starts the configured session unless it runs, then applies the torch and prepares the photo output. */
    internal fun startRunningOnQueue() {
        val ops = session ?: return
        if (!ops.isRunning) {
            ops.startRunning()
            // A torch set before the session ran does not survive its start.
            controls.applyConfiguredTorch()
            stills.prepareOnQueue()
        }
    }

    /** Stops the session if it runs or the system holds it interrupted. Session queue only. */
    internal fun stopRunningOnQueue() {
        val ops = sessionOps ?: return
        if (ops.isRunning || ops.isInterrupted) ops.stopRunning()
    }

    /** Whether setup has configured the session (and cleanupSession not yet released it). */
    internal val isConfigured: Boolean get() = session != null

    /**
     * The camera facing [lens] of [type], else the one facing the other way, else any camera of that
     * type: the order setup has always picked in.
     */
    internal fun findCamera(lens: CameraLens, type: CameraDeviceType): CaptureDeviceOps? = inputs.findCamera(lens, type)

    internal fun findCameraById(id: String): CaptureDeviceOps? = inputs.findCameraById(id)

    /**
     * Moves the session to [camera]'s input in one transaction, as a lens switch does: the session
     * keeps running and the configured torch follows. Session queue only; returns why it failed.
     */
    internal fun useDeviceOnQueue(camera: CaptureDeviceOps): CameraException? = inputs.useDeviceOnQueue(camera)

    /** Shows [layer] as the preview: mirrored for the front camera, removed with the session. Main only. */
    internal fun adoptPreviewLayer(layer: AVCaptureVideoPreviewLayer) = preview.adopt(layer, inputs.isUsingFrontCamera)

    /**
     * Stops and releases the session on the session queue, after any setup already queued, so a setup
     * still in flight cannot leave a running session behind. Call it on the main thread: it removes
     * the preview layer.
     */
    fun cleanupSession() {
        setupGeneration.incrementAndGet()
        preview.remove()
        sessionQueue.async {
            (session ?: preparedSession)?.tearDown()
            session = null
            preparedSession = null
            stills.releaseOnQueue()
            stillPreset = null
            inputs.releaseOnQueue()
            controls.forgetRequestedZoom()
        }
    }

    fun setupPreviewLayer(view: UIView) {
        val session = session?.avSession ?: return
        preview.setUp(view, session)
    }

    /** Sets the preview's orientation. Call it on main: the preview layer belongs to UIKit. */
    internal fun orientPreview(orientation: AVCaptureVideoOrientation) = preview.orient(orientation)

    /** Turns [layer] by [rotation]. Call it on main: the preview layer belongs to UIKit. */
    internal fun turnPreview(layer: AVCaptureVideoPreviewLayer, rotation: VideoRotation) = preview.turn(layer, rotation)

    fun currentVideoOrientation(): AVCaptureVideoOrientation = preview.currentVideoOrientation()

    /**
     * Sets the flash mode later captures request. It is kept even before the photo output exists;
     * a capture turns off a mode the device lacks (devices without a flash, such as iPads).
     */
    fun setFlashMode(mode: AVCaptureFlashMode) = stills.setFlashMode(mode)

    fun setTorchMode(mode: AVCaptureTorchMode) = controls.setTorchMode(mode)

    /** Applies [torchMode] to the camera in use unless the camera already reports it. Session queue only. */
    internal fun reapplyTorchOnQueue() = controls.applyConfiguredTorch()

    /**
     * Sets [mode] on [camera], which must have a torch that supports it, under its configuration lock.
     * Session queue only; returns why the camera could not be locked.
     */
    internal fun setTorchOnQueue(camera: CaptureDeviceOps, mode: AVCaptureTorchMode): CameraException? =
        controls.setTorchOnQueue(camera, mode)

    /**
     * Sets [factor], which must lie in [camera]'s zoom range, under its configuration lock. Session queue
     * only; returns why the camera could not be locked.
     */
    internal fun setZoomOnQueue(camera: CaptureDeviceOps, factor: Double): CameraException? =
        controls.setZoomOnQueue(camera, factor)

    /**
     * Sets the zoom level smoothly.
     * @param zoomFactor The desired zoom level (1.0 = no zoom), clamped to [1, getMaxZoom()]
     */
    fun setZoom(zoomFactor: Float) = controls.setZoom(zoomFactor)

    /**
     * Gets the current zoom level.
     * @return Current zoom factor (1.0 = no zoom)
     */
    fun getZoom(): Float = controls.getZoom()

    /**
     * Gets the maximum zoom level supported by the current camera.
     * @return Maximum zoom factor
     */
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

    /**
     * Issues one capture whose photo and completion go only to [callbacks], through a delegate of its
     * own that is kept alive until AVFoundation's last callback for it. Session queue only; returns
     * false, without calling [callbacks], when the camera is not ready.
     */
    internal fun capturePhotoOnQueue(callbacks: PhotoCaptureCallbacks): Boolean = stills.captureOnQueue(callbacks)

    /**
     * Switches to a specific camera device type (e.g. wide-angle, telephoto, ultra-wide)
     * while keeping the same camera position (front/back).
     */
    fun switchToDeviceType(deviceType: CameraDeviceType) = inputs.switchToDeviceType(deviceType)

    fun switchCamera() = inputs.switchCamera()

    override fun captureOutput(
        output: AVCapturePhotoOutput,
        didFinishProcessingPhoto: AVCapturePhoto,
        error: NSError?,
    ) {
        deliverPhoto(if (error == null) didFinishProcessingPhoto.fileDataRepresentation() else null, error)
    }

    /**
     * Capture delegates retained until AVFoundation's last callback for their capture: AVFoundation
     * does not retain a photo delegate, so a cancelled capture's delegate must outlive its caller.
     */
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

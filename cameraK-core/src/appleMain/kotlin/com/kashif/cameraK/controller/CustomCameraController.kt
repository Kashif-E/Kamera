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
import platform.Foundation.NSLock
import platform.Foundation.NSThread
import platform.UIKit.UIDevice
import platform.UIKit.UIView
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSObject
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_get_specific
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_set_specific
import platform.darwin.dispatch_sync
import platform.darwin.dispatch_time
import kotlin.concurrent.Volatile

private const val TAG = "CameraK"

/** Queue-specific key every controller tags its session queue with; the value is unique per controller. */
@OptIn(ExperimentalForeignApi::class)
private val sessionQueueKey = nativeHeap.alloc<ByteVar>().ptr
private val sessionQueueIds = atomic(0L)

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

internal class CustomCameraController(
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
    private var cameraDevices: CameraDeviceProvider = DiscoveryCameraDeviceProvider()
    private var previewMirroring: (AVCaptureVideoPreviewLayer?, Boolean) -> Unit = ::mirrorPreview
    private var photoCapturer: PhotoCapturer = AVPhotoCapturer
    private var previewOrienting: (
        AVCaptureVideoPreviewLayer?,
        AVCaptureVideoOrientation,
    ) -> Unit = ::orientPreviewLayer
    private var previewRotating: (AVCaptureVideoPreviewLayer, Double) -> Unit = ::rotatePreviewLayer

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
    ) : this(qualityPrioritization, initialCameraLens, aspectRatio, targetResolution, mirrorFrontCamera) {
        this.sessionFactory = sessionFactory
        this.inputConfigurator = inputConfigurator
        this.lensSwitcher = lensSwitcher
        previewMirroring?.let { this.previewMirroring = it }
        cameraDevices?.let { this.cameraDevices = it }
        photoCapturer?.let { this.photoCapturer = it }
        previewOrienting?.let { this.previewOrienting = it }
        previewRotating?.let { this.previewRotating = it }
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
            if (isOnSessionQueue) replace() else dispatch_sync(sessionQueue, replace)
        }

    /** Whether setupSession has created the session (and cleanupSession not yet released it). */
    internal val hasSession: Boolean
        get() = session != null

    private var backCamera: CaptureDeviceOps? = null
    private var frontCamera: CaptureDeviceOps? = null

    @Volatile
    private var currentCamera: CaptureDeviceOps? = null

    /** The camera's input in the session, so a switch removes it and nothing else. Session queue only. */
    private var videoInput: AVCaptureInput? = null

    @Volatile
    private var photoOutput: AVCapturePhotoOutput? = null

    /** A UIKit layer: read and written on main only. */
    var cameraPreviewLayer: AVCaptureVideoPreviewLayer? = null

    @Volatile
    private var isUsingFrontCamera = false

    /**
     * The orientation photos are captured in; AppleCameraEngine answers with its target orientation
     * when one is set, otherwise the device's.
     */
    @Volatile
    internal var captureOrientation: () -> AVCaptureVideoOrientation = { currentVideoOrientation() }

    /**
     * How the photo connection is turned for a capture: the engine answers with the coordinator's angle
     * from iOS 17 when no target orientation is set, otherwise with [captureOrientation].
     */
    @Volatile
    internal var captureRotation: () -> VideoRotation = { VideoRotation.Orientation(captureOrientation()) }

    /** Whether a recording is in progress; the engine answers from its recorder. */
    @Volatile
    internal var recordingInProgress: () -> Boolean = { false }

    /** The format captures are taken in: HEIC asks the photo output for HEVC where it can encode it. */
    @Volatile
    internal var photoFormat: ImageFormat = ImageFormat.JPEG

    /** Whether outputs get their deferred start turned off (iOS 26 and later); the engine sets it. */
    @Volatile
    internal var turnsDeferredStartOff: Boolean = false

    var onPhotoCapture: ((NSData?) -> Unit)? = null
    var onError: ((CameraException) -> Unit)? = null
    var onSessionReady: (() -> Unit)? = null

    /** The camera in use: the one the session's video input captures from. */
    internal val currentDevice: CaptureDeviceOps? get() = currentCamera

    /** The flash mode a capture's settings carry now: [flashMode], or off when the photo output lacks it. */
    internal val captureFlashMode: AVCaptureFlashMode get() = supportedFlashMode(flashMode)

    /** The flash mode captures request; a mode the photo output lacks is turned off at capture time. */
    @Volatile
    var flashMode: AVCaptureFlashMode = AVCaptureFlashModeAuto

    /** The torch mode applied to the camera in use whenever the session starts or the lens changes. */
    @Volatile
    var torchMode: AVCaptureTorchMode = AVCaptureTorchModeOff

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
        dispatch_async(sessionQueue) {
            if (setupGeneration.value != generation) return@dispatch_async
            val failure = configureOnQueue(device = null, lens = initialCameraLens, cameraDeviceType = cameraDeviceType)
            if (failure != null) {
                reportError(failure)
                return@dispatch_async
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
            val inputsReady =
                inputConfigurator?.invoke(ops, cameraDeviceType) ?: setupInputs(lens, cameraDeviceType, device)
            if (inputsReady) {
                setupPhotoOutput()
                applySessionPreset(ops)
                runPendingConfigurations()
                null
            } else {
                CameraException.DeviceNotAvailable()
            }
        } catch (e: CameraException) {
            CameraKLogger.e(TAG, "CameraK: session setup failed", e)
            e
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "CameraK: session setup failed", e)
            CameraException.ConfigurationError("Session setup failed", e)
        } finally {
            ops.commitConfiguration()
        }

        if (failure != null) {
            session = null
            photoOutput = null
            return failure
        }
        applyMaxPhotoDimensions()
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
            CameraKLogger.w(TAG, "CameraK: the session can take none of the presets $candidates; keeping its default")
            return
        }
        if (preset != candidates.first()) {
            CameraKLogger.w(TAG, "CameraK: the session cannot take preset ${candidates.first()}; using $preset")
        }
        ops.setSessionPreset(preset)
        stillPreset = preset
    }

    /** Whether captures request high resolution; QUALITY is the only prioritization that does. */
    private val highResolution: Boolean
        get() = qualityPrioritization == QualityPrioritization.QUALITY

    /** The size QUALITY captures ask for: the current camera's largest. Written on the session queue. */
    @Volatile
    private var maxPhotoDimensions: PhotoDimensions? = null

    /**
     * Opens the photo output up to the current camera's largest photo when QUALITY asks for high
     * resolution. The size must be one the camera's active format supports, so this runs on the
     * session queue once the camera (and its format) is settled: after setup and after each swap.
     */
    private fun applyMaxPhotoDimensions() {
        if (!highResolution) return
        val output = photoOutput ?: return
        val largest = currentCamera?.supportedMaxPhotoDimensions?.maxByOrNull { it.pixels }
        maxPhotoDimensions = largest
        if (largest != null) photoCapturer.setMaxPhotoDimensions(output, largest)
    }

    /** The AVFoundation prioritization for [qualityPrioritization]; null for NONE, which leaves the default. */
    private val avQualityPrioritization: AVCapturePhotoQualityPrioritization?
        get() = when (qualityPrioritization) {
            QualityPrioritization.QUALITY -> AVCapturePhotoQualityPrioritizationQuality
            QualityPrioritization.BALANCED -> AVCapturePhotoQualityPrioritizationBalanced
            QualityPrioritization.SPEED -> AVCapturePhotoQualityPrioritizationSpeed
            QualityPrioritization.NONE -> null
        }

    /** The flash modes the photo output supports with the camera now in use; none before setup. */
    internal val captureFlashModes: List<AVCaptureFlashMode>
        get() = photoOutput?.let(photoCapturer::supportedFlashModes).orEmpty()

    /** The codecs the photo output can encode a photo in with the camera now in use; none before setup. */
    internal val availablePhotoCodecs: List<String>
        get() = photoOutput?.let(photoCapturer::availablePhotoCodecTypes).orEmpty()

    /** Whether a HEIC capture can be taken: the photo output can encode HEVC with the camera now in use. */
    internal val canCaptureHeic: Boolean get() = AVVideoCodecTypeHEVC in availablePhotoCodecs

    /** The codec a capture asks for: HEVC for [photoFormat] HEIC where the output has it, JPEG otherwise. */
    private val photoCodec: String
        get() = if (photoFormat == ImageFormat.HEIC && canCaptureHeic) AVVideoCodecTypeHEVC!! else AVVideoCodecJPEG!!

    /** [mode] when the photo output supports it, otherwise off (devices without a flash, such as iPads). */
    private fun supportedFlashMode(mode: AVCaptureFlashMode): AVCaptureFlashMode =
        if (mode in captureFlashModes) mode else AVCaptureFlashModeOff

    private fun setupPhotoOutput() {
        photoOutput = AVCapturePhotoOutput()
        avQualityPrioritization?.let { photoOutput?.setMaxPhotoQualityPrioritization(it) }
        if (turnsDeferredStartOff) photoOutput?.let(photoCapturer::disableDeferredStart)

        if (session?.canAddOutput(photoOutput!!) == true) {
            session?.addOutput(photoOutput!!)
        } else {
            throw CameraException.ConfigurationError("Cannot add photo output")
        }
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

    private fun setupInputs(lens: CameraLens, cameraDeviceType: CameraDeviceType, device: CaptureDeviceOps?): Boolean {
        val camera = if (device != null) {
            if (device.isFront) frontCamera = device else backCamera = device
            device
        } else {
            backCamera = cameraDevices.find(CameraLens.BACK, cameraDeviceType)
            frontCamera = cameraDevices.find(CameraLens.FRONT, cameraDeviceType)
            when (lens) {
                CameraLens.FRONT -> frontCamera ?: backCamera
                CameraLens.BACK -> backCamera ?: frontCamera
            } ?: cameraDevices.find(null, cameraDeviceType)?.also { found ->
                if (found.isFront) frontCamera = found else backCamera = found
            }
        } ?: return false

        currentCamera = camera
        requestedZoom = null
        isUsingFrontCamera = camera.isFront

        val input = camera.makeInput().getOrElse { throw cameraInputFailure(it) }
        val ops = session ?: return false
        return if (ops.canAddInput(input)) {
            ops.addInput(input)
            videoInput = input
            true
        } else {
            false
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
            CameraKLogger.e(TAG, "CameraK: adding $what failed", failure)
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
    internal val currentVideoInput: AVCaptureInput? get() = videoInput

    /** Runs [block] on the session queue after every piece of session work already requested. */
    internal fun afterQueuedSessionWork(block: () -> Unit) {
        dispatch_async(sessionQueue, block)
    }

    /** Runs [block] on the session queue once [delayMs] have passed. */
    @OptIn(ExperimentalForeignApi::class)
    internal fun onSessionQueueAfter(delayMs: Long, block: () -> Unit) {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, delayMs * 1_000_000), sessionQueue, block)
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
            if (turnsDeferredStartOff) photoCapturer.disableDeferredStart(output)
            val audio = microphone?.let { addMicrophone(ops, it) }
            when {
                preset == null -> Unit
                ops.canSetSessionPreset(preset) -> ops.setSessionPreset(preset)
                else -> CameraKLogger.w(TAG, "CameraK: the session cannot take the recording's preset $preset")
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
        CameraKLogger.e(TAG, "CameraK: adding the microphone failed", failure)
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
                CameraKLogger.w(TAG, "CameraK: the session cannot take preset $wanted")
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
        dispatch_async(sessionQueue) { startRunningOnQueue() }
    }

    fun stopSession() {
        dispatch_async(sessionQueue) { stopRunningOnQueue() }
    }

    /** Starts the configured session unless it runs, then applies the torch and prepares the photo output. */
    internal fun startRunningOnQueue() {
        val ops = session ?: return
        if (!ops.isRunning) {
            ops.startRunning()
            // A torch set before the session ran does not survive its start.
            applyConfiguredTorch()
            preparePhotoOutput()
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
    internal fun findCamera(lens: CameraLens, type: CameraDeviceType): CaptureDeviceOps? {
        val other = if (lens == CameraLens.FRONT) CameraLens.BACK else CameraLens.FRONT
        return cameraDevices.find(lens, type) ?: cameraDevices.find(other, type) ?: cameraDevices.find(null, type)
    }

    internal fun findCameraById(id: String): CaptureDeviceOps? = cameraDevices.findById(id)

    /**
     * Moves the session to [camera]'s input in one transaction, as a lens switch does: the session
     * keeps running and the configured torch follows. Session queue only; returns why it failed.
     */
    internal fun useDeviceOnQueue(camera: CaptureDeviceOps): CameraException? {
        val ops = session ?: return CameraException.DeviceNotAvailable("The session is not set up")
        swapVideoInput(ops, camera)?.let { return it }
        if (camera.isFront) frontCamera = camera else backCamera = camera
        useCamera(camera)
        if (ops.isRunning) applyConfiguredTorch()
        return null
    }

    /** Shows [layer] as the preview: mirrored for the front camera, removed with the session. Main only. */
    internal fun adoptPreviewLayer(layer: AVCaptureVideoPreviewLayer) {
        cameraPreviewLayer = layer
        previewMirroring(layer, isUsingFrontCamera)
    }

    /**
     * Stops and releases the session on the session queue, after any setup already queued, so a setup
     * still in flight cannot leave a running session behind. Call it on the main thread: it removes
     * the preview layer.
     */
    fun cleanupSession() {
        setupGeneration.incrementAndGet()
        val layer = cameraPreviewLayer
        cameraPreviewLayer = null
        // The layer belongs to UIKit; the engine may close from any thread.
        if (layer != null) {
            if (NSThread.isMainThread) {
                layer.removeFromSuperlayer()
            } else {
                dispatch_async(dispatch_get_main_queue()) { layer.removeFromSuperlayer() }
            }
        }
        dispatch_async(sessionQueue) {
            (session ?: preparedSession)?.tearDown()
            session = null
            preparedSession = null
            photoOutput = null
            maxPhotoDimensions = null
            stillPreset = null
            currentCamera = null
            videoInput = null
            requestedZoom = null
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
        }
        previewOrienting(newPreviewLayer, currentVideoOrientation())

        view.layer.addSublayer(newPreviewLayer)
        cameraPreviewLayer = newPreviewLayer
    }

    /** Sets the preview's orientation. Call it on main: the preview layer belongs to UIKit. */
    internal fun orientPreview(orientation: AVCaptureVideoOrientation) {
        previewOrienting(cameraPreviewLayer, orientation)
    }

    /** Turns [layer] by [rotation]. Call it on main: the preview layer belongs to UIKit. */
    internal fun turnPreview(layer: AVCaptureVideoPreviewLayer, rotation: VideoRotation) {
        when (rotation) {
            is VideoRotation.Orientation -> previewOrienting(layer, rotation.orientation)
            is VideoRotation.Angle -> previewRotating(layer, rotation.degrees)
        }
    }

    private fun rotatePreviewLayer(layer: AVCaptureVideoPreviewLayer, degrees: Double) {
        layer.connection?.turn(VideoRotation.Angle(degrees))
    }

    private fun orientPreviewLayer(layer: AVCaptureVideoPreviewLayer?, orientation: AVCaptureVideoOrientation) {
        layer?.connection?.let { connection ->
            if (connection.isVideoOrientationSupported()) connection.videoOrientation = orientation
        }
    }

    @Volatile
    private var lastVideoOrientation: AVCaptureVideoOrientation = AVCaptureVideoOrientationPortrait

    fun currentVideoOrientation(): AVCaptureVideoOrientation {
        // FaceUp / FaceDown / Unknown don't correspond to a video orientation. Mapping them to
        // Portrait snaps a landscape preview to portrait and distorts it when the device lies flat
        // (#115). Keep the last valid orientation in those cases so portrait/landscape tracking
        // stays stable as the device tilts (#109).
        lastVideoOrientation = UIDevice.currentDevice.orientation.toVideoOrientation() ?: lastVideoOrientation
        return lastVideoOrientation
    }

    /**
     * Sets the flash mode later captures request. It is kept even before the photo output exists;
     * a capture turns off a mode the device lacks (devices without a flash, such as iPads).
     */
    fun setFlashMode(mode: AVCaptureFlashMode) {
        val before = supportedFlashMode(flashMode)
        flashMode = mode
        if (photoOutput != null && supportedFlashMode(mode) != mode) {
            CameraKLogger.w(TAG, "CameraK: flash mode $mode is not supported on this device; captures use OFF")
        }
        if (supportedFlashMode(mode) != before) dispatch_async(sessionQueue) { preparePhotoOutput() }
    }

    fun setTorchMode(mode: AVCaptureTorchMode) {
        torchMode = mode
        dispatch_async(sessionQueue) {
            currentCamera?.let { applyTorchMode(it, mode) }
        }
    }

    /** Applies [torchMode] to the camera in use unless the camera already reports it. Session queue only. */
    internal fun reapplyTorchOnQueue() = applyConfiguredTorch()

    /**
     * Sets [mode] on [camera], which must have a torch that supports it, under its configuration lock.
     * Session queue only; returns why the camera could not be locked.
     */
    internal fun setTorchOnQueue(camera: CaptureDeviceOps, mode: AVCaptureTorchMode): CameraException? =
        withConfigurationLock(camera, "set the torch") { camera.setTorchMode(mode) }

    /**
     * Sets [factor], which must lie in [camera]'s zoom range, under its configuration lock. Session queue
     * only; returns why the camera could not be locked.
     */
    internal fun setZoomOnQueue(camera: CaptureDeviceOps, factor: Double): CameraException? =
        withConfigurationLock(camera, "set the zoom") { camera.setVideoZoomFactor(factor) }

    /** Applies [torchMode] to the camera in use unless it is already set. Runs on the session queue. */
    private fun applyConfiguredTorch() {
        val camera = currentCamera ?: return
        val mode = torchMode
        if (camera.torchMode != mode) applyTorchMode(camera, mode)
    }

    /** Sets [mode] on [camera] when it has a torch that supports it. Runs on the session queue. */
    private fun applyTorchMode(camera: CaptureDeviceOps, mode: AVCaptureTorchMode) {
        if (!camera.hasTorch) return
        if (!camera.isTorchModeSupported(mode)) {
            CameraKLogger.w(TAG, "CameraK: the camera does not support torch mode $mode; leaving the torch as it is")
            return
        }
        withConfigurationLock(camera, "set the torch") { camera.setTorchMode(mode) }
    }

    /**
     * Runs [change] holding [camera]'s configuration lock, which AVFoundation requires for every
     * device property change; a refused lock is reported, the change skipped and the failure
     * returned. Runs on the session queue.
     */
    private inline fun withConfigurationLock(
        camera: CaptureDeviceOps,
        operation: String,
        change: () -> Unit,
    ): CameraException? {
        val refused = camera.lockForConfiguration()
        if (refused != null) {
            CameraKLogger.e(TAG, "CameraK: could not lock the camera to $operation", refused)
            val failure = CameraException.ConfigurationError("Could not lock the camera to $operation", refused)
            reportError(failure)
            return failure
        }
        try {
            change()
        } finally {
            camera.unlockForConfiguration()
        }
        return null
    }

    /** The zoom last requested, so getZoom answers at once while the change runs on the session queue. */
    @Volatile
    private var requestedZoom: Float? = null

    /**
     * Sets the zoom level smoothly.
     * @param zoomFactor The desired zoom level (1.0 = no zoom), clamped to [1, getMaxZoom()]
     */
    fun setZoom(zoomFactor: Float) {
        val camera = currentCamera ?: return
        val clampedZoom = zoomFactor.coerceIn(1.0f, getMaxZoom())
        requestedZoom = clampedZoom
        dispatch_async(sessionQueue) {
            if (currentCamera !== camera) return@dispatch_async
            withConfigurationLock(camera, "set the zoom") { camera.setVideoZoomFactor(clampedZoom.toDouble()) }
        }
    }

    /**
     * Gets the current zoom level.
     * @return Current zoom factor (1.0 = no zoom)
     */
    fun getZoom(): Float = requestedZoom ?: currentCamera?.videoZoomFactor?.toFloat() ?: 1.0f

    /**
     * Gets the maximum zoom level supported by the current camera.
     * @return Maximum zoom factor
     */
    fun getMaxZoom(): Float = currentCamera?.maxAvailableVideoZoomFactor?.toFloat() ?: 1.0f

    /**
     * Capture an image. Output quality is governed by the configured [qualityPrioritization].
     * The photo goes to [onPhotoCapture], a failure to [onError].
     */
    fun captureImage() {
        dispatch_async(sessionQueue) {
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
     */
    @OptIn(ExperimentalForeignApi::class)
    internal fun capturePhotoOnQueue(callbacks: PhotoCaptureCallbacks): Boolean {
        val output = photoOutput
        if (output == null || session?.isRunning != true) return false

        // Do NOT reconfigure the session preset here. Switching the preset synchronously right
        // before capturePhotoWithSettings disrupts auto-exposure, so the still is captured
        // mid-reconfiguration and comes out underexposed (#138). The preset is chosen once at
        // setup; memory pressure is handled by clearing buffer pools, not by downshifting capture.

        val settings = photoSettings()

        // The orientation the photo's metadata records, and the front-camera photo mirrored to match
        // the mirrored preview when configured (#112).
        val rotation = captureRotation()
        val mirrored = mirrorFrontCamera && isUsingFrontCamera

        lateinit var delegate: PhotoCaptureDelegate
        delegate = PhotoCaptureDelegate(
            object : PhotoCaptureCallbacks {
                override fun photoProcessed(data: NSData?, error: NSError?) = callbacks.photoProcessed(data, error)

                override fun captureFinished(error: NSError?) {
                    releasePhotoDelegate(delegate)
                    callbacks.captureFinished(error)
                }
            },
        )
        retainPhotoDelegate(delegate)
        photoCapturer.capture(output, settings, rotation, mirrored, delegate)
        return true
    }

    /**
     * Switches to a specific camera device type (e.g. wide-angle, telephoto, ultra-wide)
     * while keeping the same camera position (front/back).
     */
    fun switchToDeviceType(deviceType: CameraDeviceType) {
        dispatch_async(sessionQueue) {
            val ops = session ?: return@dispatch_async
            if (deviceType.toAVCaptureDeviceType() == null) return@dispatch_async
            if (refusedWhileRecording("change the camera device type")) return@dispatch_async

            // The requested type facing the current way, falling back to either way
            val lens = if (isUsingFrontCamera) CameraLens.FRONT else CameraLens.BACK
            val newDevice = cameraDevices.find(lens, deviceType)
                ?: cameraDevices.find(null, deviceType)
                ?: return@dispatch_async

            val failure = swapVideoInput(ops, newDevice)
            if (failure != null) {
                CameraKLogger.e(TAG, "CameraK: switching camera device type failed", failure)
                reportError(failure)
                return@dispatch_async
            }
            if (newDevice.isFront) frontCamera = newDevice else backCamera = newDevice
            useCamera(newDevice)
            if (ops.isRunning) applyConfiguredTorch()
        }
    }

    fun switchCamera() {
        dispatch_async(sessionQueue) {
            val ops = session ?: return@dispatch_async
            if (refusedWhileRecording("switch the lens")) return@dispatch_async
            val toFront = !isUsingFrontCamera

            val switcher = lensSwitcher
            if (switcher != null) {
                ops.beginConfiguration()
                try {
                    videoInput?.let(ops::removeInput)
                    switcher(ops, toFront)
                } finally {
                    ops.commitConfiguration()
                }
                isUsingFrontCamera = toFront
                applyPreviewMirroring()
                return@dispatch_async
            }

            val camera = if (toFront) frontCamera else backCamera
            val failure = if (camera == null) CameraException.DeviceNotAvailable() else swapVideoInput(ops, camera)
            if (failure != null) {
                CameraKLogger.e(TAG, "CameraK: switching camera failed", failure)
                reportError(failure)
                return@dispatch_async
            }
            useCamera(camera!!)
            if (ops.isRunning) applyConfiguredTorch()
        }
    }

    /**
     * Refuses a lens change while a recording runs: replacing the camera input cuts the movie file
     * output's video connection and ends the recording. Runs on the session queue.
     */
    private fun refusedWhileRecording(operation: String): Boolean {
        if (!recordingInProgress()) return false
        CameraKLogger.w(TAG, "CameraK: cannot $operation while recording; stop the recording first")
        reportError(CameraException.ConfigurationError("Cannot $operation while recording"))
        return true
    }

    /**
     * Replaces the camera input with [camera]'s in one configuration transaction. AVFoundation swaps
     * inputs on a running session, so the session keeps running and only the camera input is
     * touched (a microphone stays). A refused input puts the previous one back. Runs on the session
     * queue; returns why the swap failed, or null.
     */
    private fun swapVideoInput(ops: CaptureSessionOps, camera: CaptureDeviceOps): CameraException? {
        val newInput = camera.makeInput().getOrElse { return cameraInputFailure(it) }
        val previous = videoInput
        ops.beginConfiguration()
        try {
            previous?.let(ops::removeInput)
            if (ops.canAddInput(newInput)) {
                ops.addInput(newInput)
                videoInput = newInput
                return null
            }
            if (previous != null && ops.canAddInput(previous)) {
                ops.addInput(previous)
            }
            return CameraException.ConfigurationError("Cannot add input")
        } finally {
            ops.commitConfiguration()
        }
    }

    /** Why a camera's input could not be made, keeping what AVFoundation reported as the cause. */
    private fun cameraInputFailure(cause: Throwable): CameraException.ConfigurationError {
        val reported = (cause as? NSErrorException)?.error?.let { ": ${it.summary}" }.orEmpty()
        return CameraException.ConfigurationError("Could not open the camera$reported", cause)
    }

    /** Records [camera] as the one in use after a successful swap. Runs on the session queue. */
    private fun useCamera(camera: CaptureDeviceOps) {
        currentCamera = camera
        requestedZoom = null
        isUsingFrontCamera = camera.isFront
        applyPreviewMirroring()
        applyMaxPhotoDimensions()
        preparePhotoOutput()
    }

    /**
     * Has the photo output allocate its capture buffers now for the settings captures use, rather
     * than on the first shutter press. Runs on the session queue while the session runs: after it
     * starts, after a camera swap, and when the flash mode captures use changes.
     */
    private fun preparePhotoOutput() {
        val output = photoOutput ?: return
        if (session?.isRunning != true) return
        photoCapturer.prepare(output, listOf(photoSettings())) { _, error ->
            if (error != null) {
                val failure = CameraException.ConfigurationError(
                    "Preparing the photo output failed: ${error.summary}",
                    NSErrorException(error),
                )
                CameraKLogger.e(TAG, "CameraK: preparing the photo output failed", failure)
                reportError(failure)
            }
        }
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
        deliverPhoto(if (error == null) didFinishProcessingPhoto.fileDataRepresentation() else null, error)
    }

    // AVCam keeps each capture's delegate alive until AVFoundation's last callback for it.
    private val photoDelegatesLock = NSLock()
    private val photoDelegates = mutableSetOf<PhotoCaptureDelegate>()

    /**
     * Capture delegates retained until AVFoundation's last callback for their capture: AVFoundation
     * does not retain a photo delegate, so a cancelled capture's delegate must outlive its caller.
     */
    internal val outstandingPhotoDelegates: Int
        get() {
            photoDelegatesLock.lock()
            try {
                return photoDelegates.size
            } finally {
                photoDelegatesLock.unlock()
            }
        }

    private fun retainPhotoDelegate(delegate: PhotoCaptureDelegate) {
        photoDelegatesLock.lock()
        try {
            photoDelegates += delegate
        } finally {
            photoDelegatesLock.unlock()
        }
    }

    private fun releasePhotoDelegate(delegate: PhotoCaptureDelegate) {
        photoDelegatesLock.lock()
        try {
            photoDelegates -= delegate
        } finally {
            photoDelegatesLock.unlock()
        }
    }

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

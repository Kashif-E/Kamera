@file:OptIn(CameraKEngineApi::class, ExperimentalForeignApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineRecordingEvent
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.HardwareState
import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.engine.PreviewLayerLayout
import com.kashif.cameraK.engine.TraceNames
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.error.StorageFailure
import com.kashif.cameraK.frames.AppleFrameSource
import com.kashif.cameraK.frames.CoreVideoPixelBufferLocker
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.frames.PixelBufferLocker
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.START_TIMEOUT
import com.kashif.cameraK.state.ZoomState
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import com.kashif.cameraK.video.VideoQuality
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVCaptureFlashMode
import platform.AVFoundation.AVCaptureFlashModeAuto
import platform.AVFoundation.AVCaptureFlashModeOff
import platform.AVFoundation.AVCaptureFlashModeOn
import platform.AVFoundation.AVCaptureInput
import platform.AVFoundation.AVCaptureSessionDidStartRunningNotification
import platform.AVFoundation.AVCaptureSessionErrorKey
import platform.AVFoundation.AVCaptureSessionInterruptionEndedNotification
import platform.AVFoundation.AVCaptureSessionInterruptionReasonKey
import platform.AVFoundation.AVCaptureSessionRuntimeErrorNotification
import platform.AVFoundation.AVCaptureSessionWasInterruptedNotification
import platform.AVFoundation.AVCaptureTorchMode
import platform.AVFoundation.AVCaptureTorchModeAuto
import platform.AVFoundation.AVCaptureTorchModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
import platform.AVFoundation.AVCaptureVideoOrientation
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.CoreGraphics.CGRect
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSLock
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUUID
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIDeviceOrientationDidChangeNotification
import platform.UIKit.UIInterfaceOrientation
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume

/**
 * The iOS engine: an AVCaptureSession behind the engine contract (design §2.4). A plain class; the
 * session, its serial queue and the camera inputs belong to [custom], which this engine owns.
 *
 * - The session is made in the constructor and kept across stop and start, so a preview layer made
 *   before the first start already shows it. Inputs and outputs are configured by the first start.
 * - [start] confirms the session runs, or that the system holds it interrupted; otherwise it waits
 *   for AVFoundation's did-start-running, interruption or runtime-error notification.
 * - Those notifications are observed with the session as their object and no queue, then hop to the
 *   session queue and apply only to the start generation they arrived in. [close] removes them.
 * - A runtime error ends its generation with Failed, except a media-services reset, which interrupts
 *   the session and restarts it. A second failure before the restart ran, or a restart that does not
 *   run within the start timeout, gives Failed(Hardware).
 * - Below iOS 16 it makes no session, enumerates nothing and every call fails with
 *   `Unsupported(PLATFORM_VERSION)`: a Kotlin/Native library cannot raise its consumer's deployment
 *   target, and every iOS 16 call lives behind this check.
 *
 * - Torch and zoom are what the device reports by key-value observing, flash the photo output's mode
 *   read back on the session queue after each set and each input swap (contract rule 5). Flash, torch
 *   and zoom commands need an Open camera; the target orientation is a stored value any time before
 *   close, which captures and movies read.
 * - The device orientation is observed from UIDeviceOrientationDidChangeNotification between start
 *   and stop.
 *
 * - A capture is one session-queue block, written to app-owned storage ([capture]).
 * - Recordings are [recorder]'s, on the movie file output; stop and close finalize a running one
 *   before the camera closes.
 */
internal class AppleCameraEngine(
    private val config: CameraConfiguration,
    environment: CameraKEnvironment,
    internal val custom: CustomCameraController,
    /** How the device is held now; tests answer for the simulator, which cannot be turned. */
    private val readDeviceOrientation: () -> UIDeviceOrientation = { UIDevice.currentDevice.orientation },
    /** Begins (true) or ends (false) UIDevice's orientation notifications; tests count the calls. */
    private val generateOrientationNotifications: (Boolean) -> Unit = ::generateDeviceOrientationNotifications,
    /** Whether the app's Info.plist has a key: a test bundle's cannot change per test. */
    private val hasUsageDescription: (key: String) -> Boolean = ::hasInfoPlistKey,
    /** The rotation coordinators of iOS 17 and later; tests answer for the simulator, which has no camera. */
    private val rotationSources: RotationSourceFactory = CoordinatorRotationSources,
    /** Locks analysis frames' pixel buffers; tests count the locks. */
    private val pixelBufferLocker: PixelBufferLocker = CoreVideoPixelBufferLocker,
    private val enumerateLenses: () -> List<LensInfo> = custom::getCameraCapabilities,
) : CameraEngine {
    private val osVersion: Int = environment.runtime.osVersion ?: runningIosMajorVersion()
    private val tracer = environment.runtime.tracer ?: signpostTracer()
    private val dispatchers = environment.dispatchers

    /** The failure every call returns below iOS 16; null from 16 on. */
    private val unsupported: CameraKError? =
        if (osVersion < MINIMUM_IOS) CameraKError.Unsupported(CameraFeature.PLATFORM_VERSION) else null

    private val _state = MutableStateFlow<EngineState>(unsupported?.let(EngineState::Failed) ?: EngineState.Closed)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _hardware = MutableStateFlow(HardwareState(FlashMode.OFF, TorchMode.OFF, null, null))
    override val hardware: StateFlow<HardwareState> = _hardware.asStateFlow()

    private val _zoom = MutableStateFlow(ZoomState(1f, 1f, 1f))
    override val zoom: StateFlow<ZoomState> = _zoom.asStateFlow()

    private val _deviceOrientation = MutableStateFlow(DeviceOrientation.PORTRAIT)
    override val deviceOrientation: StateFlow<DeviceOrientation> = _deviceOrientation.asStateFlow()

    /** The orientation captures and movies are locked to; null follows the device. */
    private val target = atomic<DeviceOrientation?>(null)
    internal val targetOrientation: DeviceOrientation? get() = target.value

    /** The zoom ratio the engine re-applies after each start and camera switch. */
    @Volatile
    private var desiredZoom = 1f

    internal val desiredZoomRatio: Float get() = desiredZoom

    /** The torch the engine applies at start and after each camera switch. */
    @Volatile
    private var desiredTorch = TorchMode.OFF

    internal val desiredTorchMode: TorchMode get() = desiredTorch

    private val _capabilities = MutableStateFlow(CameraCapabilities.EMPTY)
    override val capabilities: StateFlow<CameraCapabilities> = _capabilities.asStateFlow()

    /** Recordings on the movie file output. */
    internal val recorder = AppleRecorder(
        custom = custom,
        cameraConfig = config,
        osVersion = osVersion,
        hasUsageDescription = hasUsageDescription,
        supportedQualities = ::boundQualities,
        videoRotation = ::captureRotation,
    )

    override val recordingEvents: Flow<EngineRecordingEvent> = recorder.events

    /** The movie-file-output calls recordings make; tests record them. */
    internal var movieRecorder: MovieRecorder
        get() = recorder.movieRecorder
        set(value) {
            recorder.movieRecorder = value
        }

    /** The microphone's session input for a recording with audio, or why there is none. */
    internal var microphoneInput: () -> Result<AVCaptureInput>
        get() = recorder.microphoneInput
        set(value) {
            recorder.microphoneInput = value
        }

    /** Analysis frames on one 420f video data output (design §8). */
    private val frameSource = AppleFrameSource(
        custom = custom,
        keepOutput = config.frameAnalysis,
        recordingActive = { recorder.isActive },
        rotationDegrees = ::frameRotationDegrees,
        onSampleBuffer = ::onSampleBuffer,
        locker = pixelBufferLocker,
    )
    override val frames: FrameSource = frameSource

    private val scope = CoroutineScope(
        SupervisorJob(environment.parentJob) + environment.dispatchers.default +
            CoroutineExceptionHandler { _, e -> CameraKLogger.e(TAG, "an iOS engine task failed", e) },
    )

    // Everything below the lock is read and written only while holding it.
    private val lock = NSLock()
    private var generation = 0
    private var phase = Phase.CLOSED
    private var closed = false
    private var pendingStart: PendingStart? = null
    private var firstFrame: AutoCloseable? = null

    /** A lens switch whose interval waits for the first sample buffer from [PendingSwitch.input]. */
    private var pendingSwitch: PendingSwitch? = null

    /** Set by a media-services reset's restart, cleared when the session runs again. */
    private var restartedAfterReset = false
    private var resetRestarts = 0
    private var orientationObserver: Any? = null
    private var enumeratedLenses: List<LensInfo> = emptyList()
    private var torchModesById: Map<String, Set<TorchMode>> = emptyMap()

    /** Each bound camera's flash modes, as the photo output reported them with that camera behind it. */
    private val boundFlashModes = mutableMapOf<String, Set<FlashMode>>()

    /** The image formats and recording qualities of the camera bound last; JPEG and PNG and none before. */
    private var boundImageFormats: Set<ImageFormat> = setOf(ImageFormat.JPEG, ImageFormat.PNG)
    private var boundVideoQualities: Set<VideoQuality> = emptySet()

    // Session queue only: the device whose torch and zoom the engine observes, and the observation.
    private var observedDevice: CaptureDeviceOps? = null
    private var hardwareObservation: AutoCloseable? = null

    /** The rotation coordinator of the bound camera and the newest preview layer, iOS 17 and later. Under [lock]. */
    private var rotation: Rotation? = null

    /** The preview layer made last, which the rotation coordinator turns. Under [lock]. */
    private var newestPreviewLayer: AVCaptureVideoPreviewLayer? = null

    /** The device orientation captures took last, kept while the device lies flat (#115, #109). */
    private val lastDeviceVideoOrientation = atomic(AVCaptureVideoOrientationPortrait)

    /** The reason of the latest interruption, recorded when AVFoundation posts it, before the hop to the queue. */
    private val lastInterruption = atomic<InterruptionReason?>(null)

    private val enumerated = CompletableDeferred<Unit>()
    private val closeDone = Job()
    private val starts = Mutex()

    /** Captures waiting on AVFoundation, which close completes with Closed. Under [lock]. */
    private val pendingStills = mutableSetOf<StillRequest>()
    private val heicRefusalLogged = atomic(false)

    /** Test seam: runs on the session queue between a lens switch's live check and its commit. */
    @Volatile
    internal var afterSwitchLiveCheck: () -> Unit = {}

    /** The session, made now so the preview has it before the first start; null below iOS 16. */
    private val session: CaptureSessionOps? = if (unsupported == null) custom.prepareSession() else null

    private val center = NSNotificationCenter.defaultCenter
    private val observers: List<Any> = observeSession()

    init {
        custom.captureOrientation = ::captureVideoOrientation
        custom.captureRotation = ::captureRotation
        custom.recordingInProgress = { recorder.isActive }
        custom.photoFormat = config.imageFormat
        custom.turnsDeferredStartOff = osVersion >= DEFERRED_START_IOS
        recorder.onIdle = frameSource::reconcileOnQueue
        if (unsupported == null) {
            // With frameAnalysis the frame output joins the startup transaction (design §8).
            if (config.frameAnalysis) {
                custom.afterQueuedSessionWork {
                    platformCallback("adding the frame output") { frameSource.reconcileOnQueue() }
                }
            }
            scope.launch { enumerate() }
        } else {
            enumerated.complete(Unit)
            closeDone.complete()
        }
    }

    override val preview: PlatformPreviewSource = object : PlatformPreviewSource, PreviewLayerLayout {
        override fun createPreviewLayer(): AVCaptureVideoPreviewLayer {
            val avSession = session?.avSession
            val layer = avSession?.let { AVCaptureVideoPreviewLayer(session = it) } ?: AVCaptureVideoPreviewLayer()
            // ResizeAspectFill inside a box of the configured aspect ratio (layOutPreview): the frame
            // is cropped as the still is, so the preview shows what gets captured (#119, w6 decision G1).
            layer.videoGravity = AVLayerVideoGravityResizeAspectFill
            custom.adoptPreviewLayer(layer)
            if (coordinatesRotation) {
                locked { newestPreviewLayer = layer }
                custom.afterQueuedSessionWork {
                    platformCallback("binding the rotation coordinator") { bindRotationOnQueue() }
                }
            }
            return layer
        }

        override fun layOut(
            layer: AVCaptureVideoPreviewLayer,
            bounds: CValue<CGRect>,
            interfaceOrientation: UIInterfaceOrientation?,
        ) = layOutPreview(layer, bounds, interfaceOrientation)
    }

    /**
     * Main thread: [layer], in a host of [bounds] under an interface in [interfaceOrientation], fills a
     * centred box of the configured aspect ratio (w6 decision G1) and turns: by the rotation
     * coordinator's preview angle when it drives this layer, otherwise to the interface.
     */
    internal fun layOutPreview(
        layer: AVCaptureVideoPreviewLayer,
        bounds: CValue<CGRect>,
        interfaceOrientation: UIInterfaceOrientation?,
    ) {
        layer.setFrame(previewFrame(bounds, config.aspectRatio, interfaceOrientation))
        turnPreview(layer, interfaceOrientation?.toVideoOrientation())
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────────────────────────

    override suspend fun start(desired: DesiredHardware): CameraKResult<Unit> {
        if (isClosed) return CLOSED
        unsupported?.let { error ->
            locked { if (!closed) _state.value = EngineState.Failed(error) }
            return failure(error)
        }
        if (config.analysisFormat != FrameFormat.YUV_420) {
            // AVCaptureVideoDataOutput delivers CameraK's frames in 420f only (design §6.1, §8).
            val error = CameraKError.Unsupported(CameraFeature.ANALYSIS_FORMAT)
            CameraKLogger.e(TAG, "iOS analysis frames are YUV_420; ${config.analysisFormat} fails the start")
            locked { if (!closed) _state.value = EngineState.Failed(error) }
            return failure(error)
        }
        enumerated.await()
        return starts.withLock {
            when {
                isClosed -> CLOSED
                locked { phase == Phase.LIVE } -> CameraKResult.Success(Unit)
                else -> {
                    startObservingOrientation()
                    val started = tracer.beginInterval(TraceNames.START).use { openGeneration(desired) }
                    // Observed between a start and a stop: a start that failed has nothing to stop.
                    if (started is CameraKResult.Failure) stopObservingOrientation()
                    started
                }
            }
        }
    }

    private suspend fun openGeneration(desired: DesiredHardware): CameraKResult<Unit> =
        suspendCancellableCoroutine { continuation ->
            val generation = locked {
                if (closed) return@locked null
                generation++
                phase = Phase.STARTING
                restartedAfterReset = false
                pendingStart = PendingStart(generation, continuation)
                endPendingSwitch()
                firstFrame?.close()
                firstFrame = tracer.beginInterval(TraceNames.FIRST_FRAME)
                _state.value = EngineState.Opening
                generation
            }
            if (generation == null) {
                continuation.resume(CLOSED)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { cancelStart(generation) }
            custom.afterQueuedSessionWork {
                platformCallback("starting the session") { startOnQueue(generation, desired) }
            }
        }

    /** Session queue: configures the session or moves it to the selected camera, then starts it. */
    private fun startOnQueue(generation: Int, desired: DesiredHardware) {
        if (!isCurrent(generation, Phase.STARTING)) return
        val selection = desired.selection
        val device = resolve(selection) ?: return fail(generation, CameraKError.NoCamera(selection.lens()))
        val failure = when {
            !custom.isConfigured -> custom.configureOnQueue(device, device.lens(), selection.deviceType())
            device !== custom.currentDevice -> custom.useDeviceOnQueue(device)
            else -> null
        }
        if (failure != null) return fail(generation, errorFor(failure, selection.lens()))
        target.value = desired.targetOrientation
        desiredZoom = desired.zoomRatio
        desiredTorch = desired.torchMode
        custom.setFlashMode(desired.flashMode.toAVCaptureFlashMode())
        custom.torchMode = desiredTorch.toAVCaptureTorchMode()
        bindHardware(device)
        // A stop or close since the block was queued wins: the session is not started for nobody.
        if (!isCurrent(generation, Phase.STARTING)) return
        // Starting the session applies the torch; one already running keeps the torch it had.
        if (session?.isRunning == true) custom.reapplyTorchOnQueue() else custom.startRunningOnQueue()
        applyDesiredZoom(device)
        publishHardware()
        confirm(generation)
    }

    /** Session queue: Open when the session runs, Interrupted when the system holds it; otherwise waits. */
    private fun confirm(generation: Int) {
        val ops = session ?: return
        val interrupted = ops.isInterrupted
        val running = ops.isRunning
        when {
            interrupted -> becomeLive(
                generation,
                EngineState.Interrupted(lastInterruption.value ?: InterruptionReason.OTHER),
            )
            running -> becomeLive(generation, EngineState.Open)
        }
    }

    private fun cancelStart(generation: Int) {
        val cancelled = locked {
            if (closed || this.generation != generation || phase != Phase.STARTING) return@locked false
            this.generation++
            phase = Phase.CLOSED
            pendingStart = null
            endFirstFrame()
            endPendingSwitch()
            _state.value = EngineState.Closed
            true
        }
        if (cancelled) {
            stopObservingOrientation()
            custom.afterQueuedSessionWork {
                platformCallback("stopping a cancelled start") { custom.stopRunningOnQueue() }
            }
        }
    }

    override suspend fun stop() {
        if (unsupported != null) {
            locked { if (!closed) _state.value = EngineState.Closed }
            return
        }
        // A recording is finalized before the camera closes (contract rule 7).
        if (!isClosed) recorder.finishForStop()
        val cut = locked {
            if (closed) return
            generation++
            phase = Phase.CLOSED
            endFirstFrame()
            endPendingSwitch()
            _state.value = EngineState.Closed
            pendingStart.also { pendingStart = null }
        }
        cut?.continuation?.resume(CLOSED)
        stopObservingOrientation()
        suspendCancellableCoroutine { continuation ->
            custom.afterQueuedSessionWork {
                platformCallback("releasing a prepared recording") { recorder.releasePreparedOnQueue() }
                platformCallback("stopping the session") { custom.stopRunningOnQueue() }
                platformCallback("closing the rotation coordinator") { unbindRotationOnQueue() }
                continuation.resume(Unit)
            }
        }
    }

    override fun close() {
        val cut = locked {
            if (closed) return
            closed = true
            generation++
            phase = Phase.CLOSED
            endFirstFrame()
            endPendingSwitch()
            _state.value = EngineState.Closed
            pendingStart.also { pendingStart = null }
        }
        cut?.continuation?.resume(CLOSED)
        locked { pendingStills.toList().also { pendingStills.clear() } }.forEach { it.settle(CLOSED) }
        observers.forEach(center::removeObserver)
        stopObservingOrientation()
        enumerated.complete(Unit)
        if (session != null) {
            // A recording is finalized before the session is released (contract rule 7).
            recorder.finishForClose {
                custom.cleanupSession()
                custom.afterQueuedSessionWork {
                    stopObservingHardware()
                    unbindRotationOnQueue()
                    closeDone.complete()
                }
            }
        }
        scope.cancel()
    }

    override suspend fun closeAndJoin() {
        close()
        closeDone.join()
    }

    // ─── Platform callbacks ─────────────────────────────────────────────────────────────────────

    /**
     * Registers the four session observers with the session as their object and no queue. Each
     * reads what it needs on the posting thread, then hops to the session queue carrying the
     * generation it arrived in.
     */
    private fun observeSession(): List<Any> {
        val avSession = session?.avSession ?: return emptyList()
        fun observe(name: String?, what: String, handle: (Int, NSNotification) -> Unit): Any =
            center.addObserverForName(name, avSession, null) { notification ->
                platformCallback(what) {
                    val generation = locked { generation }
                    if (notification != null) {
                        custom.afterQueuedSessionWork { platformCallback(what) { handle(generation, notification) } }
                    }
                }
            }
        return listOf(
            center.addObserverForName(AVCaptureSessionWasInterruptedNotification, avSession, null) { notification ->
                platformCallback("a session interruption") {
                    val code = notification?.userInfo?.get(AVCaptureSessionInterruptionReasonKey) as? NSNumber
                    val reason = interruptionReasonFor(code?.longValue)
                    lastInterruption.value = reason
                    val generation = locked { generation }
                    custom.afterQueuedSessionWork {
                        platformCallback("a session interruption") { onInterrupted(generation, reason) }
                    }
                }
            },
            observe(AVCaptureSessionInterruptionEndedNotification, "an interruption ending") { generation, _ ->
                onInterruptionEnded(generation)
            },
            observe(AVCaptureSessionDidStartRunningNotification, "the session starting") { generation, _ ->
                onDidStartRunning(generation)
            },
            observe(AVCaptureSessionRuntimeErrorNotification, "a session runtime error") { generation, notification ->
                onRuntimeError(generation, notification.userInfo?.get(AVCaptureSessionErrorKey) as? NSError)
            },
        )
    }

    private fun onInterrupted(generation: Int, reason: InterruptionReason) {
        if (isLiveOrStarting(generation)) becomeLive(generation, EngineState.Interrupted(reason))
    }

    private fun onInterruptionEnded(generation: Int) {
        if (!isLiveOrStarting(generation)) return
        if (session?.isRunning == true) becomeLive(generation, EngineState.Open)
    }

    private fun onDidStartRunning(generation: Int) {
        val stale = locked { this.generation == generation && phase == Phase.CLOSED && !closed }
        if (stale) {
            // An open that arrives after the start it belonged to was stopped: the camera stays closed.
            custom.stopRunningOnQueue()
            return
        }
        if (isLiveOrStarting(generation)) confirm(generation)
    }

    private fun onRuntimeError(generation: Int, error: NSError?) {
        if (!isLiveOrStarting(generation)) return
        if (error == null) {
            CameraKLogger.e(TAG, "the session reported a runtime error without an NSError")
            return fail(generation, CameraKError.Hardware(null))
        }
        val next = sessionErrorState(error)
        if (next is EngineState.Failed) return fail(generation, next.error)
        val hardware = CameraKError.Hardware(error.code.toInt(), NSErrorException(error))
        // A reset restarts the session once; a second failure in a row, before the restart ran, ends the generation.
        val restart = locked {
            if (restartedAfterReset) return@locked null
            restartedAfterReset = true
            ++resetRestarts
        } ?: return fail(generation, hardware)
        becomeLive(generation, next)
        custom.startRunningOnQueue()
        confirm(generation)
        failUnlessRunningWithinTheStartTimeout(generation, restart, hardware)
    }

    /**
     * A restart that leaves the session neither running nor interrupted may still run on its own, so the
     * engine waits for did-start-running as a start does, for [START_TIMEOUT], and then fails with [error].
     */
    private fun failUnlessRunningWithinTheStartTimeout(generation: Int, restart: Int, error: CameraKError) {
        scope.launch {
            delay(START_TIMEOUT)
            custom.afterQueuedSessionWork {
                platformCallback("a restart after a media-services reset") {
                    val pending = locked { restartedAfterReset && resetRestarts == restart }
                    val ops = session
                    if (pending && ops != null && !ops.isRunning && !ops.isInterrupted) fail(generation, error)
                }
            }
        }
    }

    /**
     * Runs one platform callback. Nothing it throws crosses the GCD block or the notification it
     * runs in: the throwable is logged and the callback does nothing more.
     */
    private inline fun platformCallback(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "handling $what failed", e)
        }
    }

    // ─── State transitions ──────────────────────────────────────────────────────────────────────

    private fun becomeLive(generation: Int, next: EngineState) {
        val started = locked {
            if (closed || this.generation != generation) return
            if (phase != Phase.STARTING && phase != Phase.LIVE) return
            phase = Phase.LIVE
            _state.value = next
            if (next == EngineState.Open) {
                // With a frame output the first frame is its first sample buffer (onSampleBuffer).
                if (!frameSource.outputAttached) endFirstFrame()
                restartedAfterReset = false
            }
            pendingStart.also { pendingStart = null }
        }
        started?.continuation?.resume(CameraKResult.Success(Unit))
    }

    private fun fail(generation: Int, error: CameraKError) {
        val failedStart = locked {
            if (closed || this.generation != generation) return
            if (phase != Phase.STARTING && phase != Phase.LIVE) return
            phase = Phase.FAILED
            endFirstFrame()
            endPendingSwitch()
            _state.value = EngineState.Failed(error)
            pendingStart.also { pendingStart = null }
        }
        CameraKLogger.e(TAG, "the camera session failed", error.cause)
        failedStart?.continuation?.resume(failure(error))
    }

    private fun isCurrent(generation: Int, expected: Phase): Boolean =
        locked { !closed && this.generation == generation && phase == expected }

    private fun isLiveOrStarting(generation: Int): Boolean =
        locked { !closed && this.generation == generation && (phase == Phase.STARTING || phase == Phase.LIVE) }

    private val isClosed: Boolean get() = locked { closed }

    // Lock held.
    private fun endFirstFrame() {
        firstFrame?.close()
        firstFrame = null
    }

    // Lock held: a switch whose first frame never came ends here, marked noFrame.
    private fun endPendingSwitch() {
        val switch = pendingSwitch ?: return
        pendingSwitch = null
        tracer.marker(AppleTraceNames.LENS_SWITCH_NO_FRAME)
        switch.interval.close()
    }

    /**
     * The frame output's queue, before any analyzer: the start's first sample buffer ends its
     * first-frame interval, and the first one through the new camera's input ends a lens switch's.
     */
    private fun onSampleBuffer(input: AVCaptureInput?) {
        locked {
            if (!closed && (phase == Phase.STARTING || phase == Phase.LIVE)) endFirstFrame()
            val switch = pendingSwitch
            if (switch != null && input != null && switch.input === input) {
                pendingSwitch = null
                switch.interval.close()
            }
        }
    }

    /**
     * Clockwise degrees that turn a frame upright: the video data output delivers the sensor's
     * landscape buffer, which the back camera shows upright with the device turned left and the front
     * camera with it turned right (the front's mirroring is not part of the rotation).
     */
    private fun frameRotationDegrees(): Int {
        val front = custom.currentDevice?.isFront == true
        return when (_deviceOrientation.value) {
            DeviceOrientation.PORTRAIT -> if (front) DEGREES_270 else DEGREES_90
            DeviceOrientation.LANDSCAPE_LEFT -> if (front) DEGREES_180 else 0
            DeviceOrientation.PORTRAIT_UPSIDE_DOWN -> if (front) DEGREES_90 else DEGREES_270
            DeviceOrientation.LANDSCAPE_RIGHT -> if (front) 0 else DEGREES_180
        }
    }

    // ─── Commands ───────────────────────────────────────────────────────────────────────────────

    override suspend fun selectCamera(selection: CameraSelection): CameraKResult<LensInfo> {
        unusable()?.let { return it }
        enumerated.await()
        return suspendCancellableCoroutine { continuation -> switchCamera(selection) { continuation.resume(it) } }
    }

    /**
     * Moves the open session to the camera [selection] names, with the 1.x lens switch, and passes
     * the camera live afterwards to [done] on the session queue. The work is queued before this
     * returns, so it runs after any session work already requested. The switch is one
     * `CameraK.lensSwitch` interval. With no frame output attached it ends when the swap has
     * committed (the session keeps running through it); with one, at the first sample buffer through
     * the new camera's input, or, marked noFrame, at the engine's stop, close, failure or next switch.
     * A switch whose generation a stop or close cut while it was committing ends at once, marked noFrame;
     * a refused switch ends at once, marked failed.
     */
    internal fun switchCamera(selection: CameraSelection, done: (CameraKResult<LensInfo>) -> Unit) {
        locked { endPendingSwitch() }
        val interval = tracer.beginInterval(TraceNames.LENS_SWITCH)
        custom.afterQueuedSessionWork {
            val generation = locked { this.generation }
            val result = try {
                switchOnQueue(selection, generation)
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "switching the camera failed", e)
                failure(CameraKError.Internal(e))
            }
            // The input whose first sample buffer ends the interval, when a frame output is attached.
            val awaited = custom.currentVideoInput
                .takeIf { result is CameraKResult.Success && frameSource.outputAttached }
            // The phase and generation are checked under the lock that publishes the switch, so a stop
            // since the live check ends the switch here instead of leaving it waiting.
            val pending = awaited != null &&
                locked {
                    if (closed || this.generation != generation || phase != Phase.LIVE) return@locked false
                    endPendingSwitch()
                    pendingSwitch = PendingSwitch(interval, awaited)
                    true
                }
            if (!pending) {
                when {
                    result is CameraKResult.Failure -> tracer.marker(AppleTraceNames.LENS_SWITCH_FAILED)
                    awaited != null -> tracer.marker(AppleTraceNames.LENS_SWITCH_NO_FRAME)
                }
                interval.close()
            }
            done(result)
        }
    }

    private fun switchOnQueue(selection: CameraSelection, generation: Int): CameraKResult<LensInfo> {
        unusable()?.let { return it }
        if (!isCurrent(generation, Phase.LIVE)) return failure(CameraKError.NotReady)
        afterSwitchLiveCheck()
        val device = resolve(selection) ?: return failure(CameraKError.NoCamera(selection.lens()))
        if (custom.recordingInProgress()) return failure(CameraKError.Busy(CameraOperation.LENS))
        // A refused input puts the previous camera back, inside the same transaction.
        custom.useDeviceOnQueue(device)?.let { return failure(errorFor(it, selection.lens())) }
        bindHardware(device)
        applyDesiredZoom(device)
        publishHardware()
        return CameraKResult.Success(lensInfo(device))
    }

    /**
     * Validates [mode] against the camera's flash modes, stores it on the session queue, and returns
     * and publishes the mode the photo output will use, read back after the store (contract rule 5).
     */
    override suspend fun setFlashMode(mode: FlashMode): CameraKResult<FlashMode> {
        notOpen()?.let { return it }
        val camera = custom.currentDevice?.let(::lensInfo)
        if (camera != null && mode !in capabilities.value.supportedFlashModes(camera.id)) {
            val feature = if (mode == FlashMode.AUTO) CameraFeature.FLASH_AUTO else CameraFeature.FLASH_ON
            return failure(CameraKError.Unsupported(feature))
        }
        return onSessionQueue("setting the flash mode") {
            custom.setFlashMode(mode.toAVCaptureFlashMode())
            publishHardware()
            CameraKResult.Success(custom.captureFlashMode.toFlashMode())
        }
    }

    /**
     * Takes one still (design §2.4): a session-queue block builds the settings from the photo output
     * as it is then and hands them to it; AVFoundation's callbacks stay on its own queue. The photo is
     * written to app-owned storage ([writeStill]) off the session queue. One `CameraK.capture`
     * interval covers the call. Close completes a capture still waiting with Closed; a cancelled
     * capture's delegate lives on until AVFoundation's last callback, and its photo goes nowhere.
     */
    override suspend fun capture(): CameraKResult<CapturedImage> {
        notOpen()?.let { return it }
        return tracer.beginInterval(TraceNames.CAPTURE).use { takeStill() }
    }

    private suspend fun takeStill(): CameraKResult<CapturedImage> {
        val photo = suspendCancellableCoroutine { continuation ->
            val request = StillRequest(continuation)
            if (!locked { !closed && pendingStills.add(request) }) {
                continuation.resume(CLOSED)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { request.detach() }
            custom.afterQueuedSessionWork {
                val outcome = try {
                    stillRefusal() ?: if (custom.capturePhotoOnQueue(request)) null else failure(CameraKError.NotReady)
                } catch (e: Exception) {
                    CameraKLogger.e(TAG, "issuing a capture failed", e)
                    failure(CameraKError.Internal(e))
                }
                outcome?.let(request::settle)
            }
        }
        val data = when (photo) {
            is CameraKResult.Failure -> return photo
            is CameraKResult.Success -> photo.value
        }
        return withContext(dispatchers.io) { saveStill(data) }
    }

    /** Session queue: why no capture can be issued now; null when one can. */
    private fun stillRefusal(): CameraKResult.Failure? {
        unusable()?.let { return it }
        if (config.imageFormat != ImageFormat.HEIC || custom.canCaptureHeic) return null
        if (heicRefusalLogged.compareAndSet(expect = false, update = true)) {
            CameraKLogger.e(TAG, "this camera cannot encode HEIC (no HEVC photo codec); captures are refused")
        }
        return failure(CameraKError.Unsupported(CameraFeature.IMAGE_FORMAT))
    }

    /** Writes [photo] where the configuration says, in its format and aspect ratio. Runs off the session queue. */
    private fun saveStill(photo: NSData): CameraKResult<CapturedImage> {
        val format = config.imageFormat
        val path = try {
            "${appOwnedDirectory(config.outputDirectory)}/IMG_${NSUUID().UUIDString}.${format.extension}"
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "creating the capture's directory failed", e)
            return failure(CameraKError.Storage(StorageFailure.NOT_WRITABLE, cause = e))
        }
        val written = writeStill(photo, format, config.aspectRatio, path).getOrElse { e ->
            CameraKLogger.e(TAG, "writing the captured photo failed", e)
            removeFileIfPresent(path)
            val onDisk = e.cause is NSErrorException
            return failure(
                if (onDisk) CameraKError.Storage(StorageFailure.NOT_WRITABLE, cause = e) else CameraKError.Internal(e),
            )
        }
        return CameraKResult.Success(CapturedImage(path, format, written.width, written.height))
    }

    /**
     * One capture's AVFoundation callbacks, which arrive here and only here. It settles once: with the
     * photo, the error that prevented it, or Closed; a detached (cancelled) capture's photo goes nowhere.
     */
    private inner class StillRequest(private val continuation: CancellableContinuation<CameraKResult<NSData>>) :
        PhotoCaptureCallbacks {
        private val settled = atomic(false)
        private val detached = atomic(false)

        fun detach() {
            detached.value = true
        }

        fun settle(result: CameraKResult<NSData>) {
            if (!settled.compareAndSet(expect = false, update = true)) return
            locked { pendingStills.remove(this) }
            if (detached.value) {
                CameraKLogger.d(TAG, "discarding the photo of a cancelled capture")
                return
            }
            continuation.resume(result)
        }

        override fun photoProcessed(data: NSData?, error: NSError?) = settle(
            when {
                error != null -> failure(photoError(error))
                data == null -> failure(CameraKError.Internal(IllegalStateException("AVFoundation delivered no photo")))
                else -> CameraKResult.Success(data)
            },
        )

        override fun captureFinished(error: NSError?) = settle(
            failure(
                error?.let(::photoError)
                    ?: CameraKError.Internal(
                        IllegalStateException("AVFoundation finished the capture without a photo"),
                    ),
            ),
        )
    }

    private fun photoError(error: NSError): CameraKError {
        CameraKLogger.e(TAG, "the photo capture failed", NSErrorException(error))
        return cameraKErrorFor(error)
    }

    /**
     * Clamps [ratio] into the camera's available zoom range on the session queue before the device sees
     * it, since AVFoundation raises an exception nothing can catch beyond the format's range and clamps
     * silently to the available one ([CaptureDeviceOps.minVideoZoomFactor]), and returns the clamped
     * ratio without waiting for the device's report. A camera with a fixed zoom takes only that ratio.
     */
    override suspend fun setZoom(ratio: Float): CameraKResult<ZoomState> {
        notOpen()?.let { return it }
        if (ratio.isNaN()) return failure(CameraKError.Unsupported(CameraFeature.ZOOM))
        return onSessionQueue("setting the zoom") {
            val device = custom.currentDevice ?: return@onSessionQueue failure(CameraKError.NotReady)
            val min = device.minVideoZoomFactor
            val max = device.maxAvailableVideoZoomFactor
            if (max <= min) {
                return@onSessionQueue if (ratio.toDouble() == min) {
                    CameraKResult.Success(ZoomState(min.toFloat(), min.toFloat(), max.toFloat()))
                } else {
                    failure(CameraKError.Unsupported(CameraFeature.ZOOM))
                }
            }
            val factor = ratio.toDouble().coerceIn(min, max)
            custom.setZoomOnQueue(device, factor)?.let { return@onSessionQueue hardwareFailure("setting the zoom", it) }
            desiredZoom = factor.toFloat()
            CameraKResult.Success(ZoomState(factor.toFloat(), min.toFloat(), max.toFloat()))
        }
    }

    /**
     * Sets the torch on the session queue when the camera's device supports [mode], the source of its
     * torch capabilities; [hardware] follows when the device reports it lit or dark.
     */
    override suspend fun setTorchMode(mode: TorchMode): CameraKResult<TorchMode> {
        notOpen()?.let { return it }
        return onSessionQueue("setting the torch") {
            val device = custom.currentDevice ?: return@onSessionQueue failure(CameraKError.NotReady)
            val avMode = mode.toAVCaptureTorchMode()
            if (!device.hasTorch || !device.isTorchModeSupported(avMode)) {
                return@onSessionQueue failure(CameraKError.Unsupported(CameraFeature.TORCH))
            }
            custom.setTorchOnQueue(device, avMode)?.let {
                return@onSessionQueue hardwareFailure("setting the torch", it)
            }
            desiredTorch = mode
            custom.torchMode = avMode
            CameraKResult.Success(mode)
        }
    }

    /**
     * Stores the target in any state before close; while a camera is bound it is reported in
     * [hardware] before this returns. The next start sets it from its desired hardware.
     */
    override suspend fun setTargetOrientation(orientation: DeviceOrientation?): CameraKResult<Unit> {
        unusable()?.let { return it }
        target.value = orientation
        return onSessionQueue("setting the target orientation") {
            publishHardware()
            CameraKResult.Success(Unit)
        }
    }

    /** Adds the movie output (and the microphone, for audio) now, so the next start commits nothing. */
    override suspend fun prepareRecording(config: VideoConfiguration): CameraKResult<Unit> {
        notOpen()?.let { return it }
        return recorder.prepare(config)
    }

    /** Returns once AVFoundation reports the recording started; its value is the file's path. */
    override suspend fun startRecording(config: VideoConfiguration): CameraKResult<String> {
        notOpen()?.let { return it }
        return recorder.start(config)
    }

    /** Reaches the movie output in any state before close, so a recording can always be ended. */
    override suspend fun stopRecording(): CameraKResult<RecordedVideo> {
        unusable()?.let { return it }
        return recorder.stop(RecordingEndReason.USER)
    }

    override suspend fun pauseRecording(): CameraKResult<Unit> {
        unusable()?.let { return it }
        return recorder.pause()
    }

    override suspend fun resumeRecording(): CameraKResult<Unit> {
        unusable()?.let { return it }
        return recorder.resume()
    }

    /** The recording qualities the bound camera can deliver: those whose session preset it supports. */
    private fun boundQualities(): Set<VideoQuality> {
        val device = custom.currentDevice ?: return emptySet()
        return VideoQuality.entries.filter { device.supportsSessionPreset(it.sessionPreset) }.toSet()
    }

    private fun unusable(): CameraKResult.Failure? = when {
        isClosed -> CLOSED
        unsupported != null -> failure(unsupported)
        else -> null
    }

    /** Hardware commands need an Open camera, as on every engine and the fake. */
    private fun notOpen(): CameraKResult.Failure? =
        unusable() ?: failure(CameraKError.NotReady).takeIf { _state.value != EngineState.Open }

    /** Runs [block] on the session queue and returns its result; nothing it throws leaves the queue. */
    private suspend fun <T> onSessionQueue(what: String, block: () -> CameraKResult<T>): CameraKResult<T> =
        suspendCancellableCoroutine { continuation ->
            custom.afterQueuedSessionWork {
                val result = try {
                    unusable() ?: block()
                } catch (e: Exception) {
                    CameraKLogger.e(TAG, "$what failed", e)
                    failure(CameraKError.Internal(e))
                }
                continuation.resume(result)
            }
        }

    private fun hardwareFailure(what: String, cause: CustomCameraController.CameraException): CameraKResult.Failure {
        CameraKLogger.e(TAG, "$what failed", cause)
        return failure(errorFor(cause, null))
    }

    /** The orientation captures and movies take: the target when one is set, otherwise the device's. */
    internal fun captureVideoOrientation(): AVCaptureVideoOrientation =
        target.value?.toVideoOrientation() ?: deviceVideoOrientation()

    /**
     * How photos and movies are turned (design §2.4): the target orientation when one is set, else the
     * rotation coordinator's horizon-level capture angle from iOS 17, else the device's orientation.
     */
    internal fun captureRotation(): VideoRotation {
        target.value?.let { return VideoRotation.Orientation(it.toVideoOrientation()) }
        locked { rotation }?.let { return VideoRotation.Angle(it.source.captureAngle) }
        return VideoRotation.Orientation(deviceVideoOrientation())
    }

    /**
     * How the device is held, as a video orientation. Face up, face down and unknown name none, and
     * mapping them to portrait would snap a landscape capture to portrait while the device lies flat
     * (#115), so they keep the last one (#109).
     */
    private fun deviceVideoOrientation(): AVCaptureVideoOrientation {
        val now = readDeviceOrientation().toVideoOrientation() ?: return lastDeviceVideoOrientation.value
        lastDeviceVideoOrientation.value = now
        return now
    }

    /** Main thread: [layer] by the coordinator's preview angle when it drives the layer, else to [interfaceOrientation]. */
    private fun turnPreview(layer: AVCaptureVideoPreviewLayer, interfaceOrientation: AVCaptureVideoOrientation?) {
        val driven = locked { rotation }?.takeIf { it.layer === layer }
        val turn = when {
            driven != null -> VideoRotation.Angle(driven.source.previewAngle)
            interfaceOrientation != null -> VideoRotation.Orientation(interfaceOrientation)
            else -> return
        }
        custom.turnPreview(layer, turn)
    }

    // ─── Rotation coordinator (iOS 17 and later) ────────────────────────────────────────────────

    /** The coordinator made for [device] and [layer]. */
    private class Rotation(
        val device: CaptureDeviceOps,
        val layer: AVCaptureVideoPreviewLayer?,
        val source: RotationSource,
    )

    private val coordinatesRotation: Boolean get() = osVersion >= ROTATION_COORDINATOR_IOS

    /**
     * Session queue: a coordinator for the bound camera and the newest preview layer, in place of one
     * made for another camera or layer, and the layer turned to its preview angle. Nothing below iOS
     * 17, before a camera is bound, or for a camera AVFoundation does not own.
     */
    private fun bindRotationOnQueue() {
        if (!coordinatesRotation) return
        val device = observedDevice ?: return
        val layer = locked { newestPreviewLayer }
        val current = locked { rotation }
        if (current != null && current.device === device && current.layer === layer) return
        val made = atomic<Rotation?>(null)
        val source = try {
            rotationSources.make(device, layer) { angle ->
                made.value?.let { platformCallback("a preview angle report") { onPreviewAngle(it, angle) } }
            }
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "making the rotation coordinator failed; rotation follows the device", e)
            null
        }
        val next = source?.let { Rotation(device, layer, it) }
        made.value = next
        val replaced = locked {
            if (closed) {
                next?.source?.close()
                return
            }
            rotation.also { rotation = next }
        }
        replaced?.source?.close()
        if (next?.layer != null) onPreviewAngle(next, next.source.previewAngle)
    }

    /** Session queue: the coordinator closed, so nothing reports angles for a camera that stopped. */
    private fun unbindRotationOnQueue() {
        locked { rotation.also { rotation = null } }?.source?.close()
    }

    /** From any thread: [made]'s layer turned to [angle] on main, unless another coordinator replaced it since. */
    private fun onPreviewAngle(made: Rotation, angle: Double) {
        val layer = made.layer ?: return
        dispatch_async(dispatch_get_main_queue()) {
            platformCallback("turning the preview") {
                if (locked { rotation === made }) custom.turnPreview(layer, VideoRotation.Angle(angle))
            }
        }
    }

    // ─── Device orientation ─────────────────────────────────────────────────────────────────────

    /** From start until stop or close: UIDeviceOrientationDidChangeNotification, read through the reader seam. */
    private fun startObservingOrientation() {
        val added = locked {
            if (closed || orientationObserver != null) return@locked false
            orientationObserver = center.addObserverForName(UIDeviceOrientationDidChangeNotification, null, null) {
                platformCallback("a device orientation change") { updateDeviceOrientation() }
            }
            true
        }
        if (!added) return
        generateOrientationNotifications(true)
        platformCallback("reading the device orientation") { updateDeviceOrientation() }
    }

    private fun stopObservingOrientation() {
        val observer = locked { orientationObserver.also { orientationObserver = null } } ?: return
        center.removeObserver(observer)
        generateOrientationNotifications(false)
    }

    /** Face up, face down and unknown name no orientation, so they keep the last one. */
    private fun updateDeviceOrientation() {
        val orientation = readDeviceOrientation().toDeviceOrientation() ?: return
        locked {
            if (!closed) _deviceOrientation.value = orientation
        }
    }

    // ─── Platform reads ─────────────────────────────────────────────────────────────────────────

    private suspend fun enumerate() {
        val (lenses, torchModes) = try {
            val lenses = enumerateLenses()
            lenses to lenses.associate { it.id to torchModesOf(it) }
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "enumerating the cameras failed; reporting none", e)
            emptyList<LensInfo>() to emptyMap()
        }
        locked {
            enumeratedLenses = lenses
            torchModesById = torchModes
        }
        publishCapabilities()
        enumerated.complete(Unit)
    }

    /** The torch modes [lens]'s device supports; from its hasFlash when the device cannot be found. */
    private fun torchModesOf(lens: LensInfo): Set<TorchMode> {
        val device = custom.findCameraById(lens.id)
            ?: return if (lens.hasFlash) setOf(TorchMode.OFF, TorchMode.ON) else setOf(TorchMode.OFF)
        if (!device.hasTorch) return setOf(TorchMode.OFF)
        return setOf(TorchMode.OFF) + listOf(TorchMode.ON, TorchMode.AUTO)
            .filter { device.isTorchModeSupported(it.toAVCaptureTorchMode()) }
    }

    private fun publishCapabilities() {
        val capabilities = locked {
            if (closed) return
            capabilitiesOf(
                enumeratedLenses,
                torchModesById,
                boundFlashModes.toMap(),
                boundImageFormats,
                boundVideoQualities,
            )
        }
        _capabilities.value = capabilities
    }

    /**
     * A lens's flash modes are the photo output's once the lens has been bound, and come from its
     * hasFlash before; its torch modes are its device's. The image formats and recording qualities are
     * the bound camera's.
     */
    private fun capabilitiesOf(
        lenses: List<LensInfo>,
        torchModes: Map<String, Set<TorchMode>>,
        boundFlash: Map<String, Set<FlashMode>>,
        imageFormats: Set<ImageFormat>,
        videoQualities: Set<VideoQuality>,
    ) = CameraCapabilities(
        allLenses = lenses,
        flashModes = lenses.associate {
            it.id to (
                boundFlash[it.id]
                    ?: if (it.hasFlash) setOf(FlashMode.OFF, FlashMode.AUTO, FlashMode.ON) else setOf(FlashMode.OFF)
                )
        },
        torchModes = lenses.associate { it.id to (torchModes[it.id] ?: setOf(TorchMode.OFF)) },
        // AVCaptureFileOutput pauses from iOS 18.
        supportsPauseResume = osVersion >= PAUSE_RESUME_IOS,
        // The qualities whose preset the bound camera delivers; none before the first bind.
        supportedVideoQualities = videoQualities,
        // AVCaptureMovieFileOutput records at its session preset's rate; no bitrate is applied (§6.3).
        supportsVideoBitrate = false,
        // 16:9 and 1:1 are crops of the full-resolution photo (StillWriter), so 1:1 is a true square.
        supportedAspectRatios = AspectRatio.entries.toSet(),
        // HEIC where the bound camera's photo output can encode HEVC.
        supportedImageFormats = imageFormats,
        // AVCaptureVideoDataOutput delivers CameraK's frames in 420f only (design §8).
        supportedAnalysisFormats = setOf(FrameFormat.YUV_420),
        supportsSquareCapture = true,
    )

    private fun resolve(selection: CameraSelection): CaptureDeviceOps? = when (selection) {
        is CameraSelection.ById -> custom.findCameraById(selection.id)
        is CameraSelection.ByLens -> custom.findCamera(selection.lens, selection.deviceType)
        is CameraSelection.Default -> custom.findCamera(CameraLens.BACK, selection.deviceType)
    }

    /**
     * Session queue, after [device] became the session's camera: its flash modes, image formats and
     * recording qualities from the photo output and the device now behind it, and the device's torch and
     * zoom observed in place of the previous camera's.
     */
    private fun bindHardware(device: CaptureDeviceOps) {
        val flash = custom.captureFlashModes.map { it.toFlashMode() }.toSet() + FlashMode.OFF
        val formats =
            setOf(ImageFormat.JPEG, ImageFormat.PNG) + listOfNotNull(ImageFormat.HEIC.takeIf { custom.canCaptureHeic })
        val qualities = boundQualities()
        locked {
            boundFlashModes[device.uniqueId] = flash
            boundImageFormats = formats
            boundVideoQualities = qualities
        }
        publishCapabilities()
        if (observedDevice !== device) {
            stopObservingHardware()
            observeHardwareOf(device)
        }
        bindRotationOnQueue()
    }

    /** Session queue: [device]'s torch and zoom observed. */
    private fun observeHardwareOf(device: CaptureDeviceOps) {
        observedDevice = device
        hardwareObservation = device.observeHardware {
            platformCallback("a torch or zoom report") {
                custom.afterQueuedSessionWork {
                    platformCallback("a torch or zoom report") { onHardwareReport() }
                }
            }
        }
    }

    // Session queue only.
    private fun stopObservingHardware() {
        hardwareObservation?.close()
        hardwareObservation = null
        observedDevice = null
    }

    /**
     * Session queue: a key-value report, published while a generation is starting or live. A report from
     * a camera switched away from, queued before its observation closed, publishes the live camera's
     * values again, which [publishHardware] reads from the device in use, so it changes nothing.
     */
    private fun onHardwareReport() {
        if (!locked { !closed && (phase == Phase.STARTING || phase == Phase.LIVE) }) return
        publishHardware()
    }

    /** Session queue: the desired zoom, clamped into [device]'s range, unless the device reports it already. */
    private fun applyDesiredZoom(device: CaptureDeviceOps) {
        val min = device.minVideoZoomFactor
        val max = device.maxAvailableVideoZoomFactor
        if (max < min) return
        val factor = desiredZoom.toDouble().coerceIn(min, max)
        if (device.videoZoomFactor == factor) return
        custom.setZoomOnQueue(device, factor)?.let { failure ->
            CameraKLogger.e(TAG, "re-applying the zoom failed", failure)
        }
    }

    /**
     * Session queue: the camera, torch and zoom as the device reports them, the flash the photo output
     * will use, and the target orientation.
     */
    private fun publishHardware() {
        val device = custom.currentDevice ?: return
        if (isClosed) return
        _hardware.value = HardwareState(
            flashMode = custom.captureFlashMode.toFlashMode(),
            torchMode = when {
                device.torchMode == AVCaptureTorchModeAuto -> TorchMode.AUTO
                // A torch the system shut off for heat keeps its mode but is dark.
                device.torchMode == AVCaptureTorchModeOn && device.isTorchActive -> TorchMode.ON
                else -> TorchMode.OFF
            },
            camera = lensInfo(device),
            targetOrientation = target.value,
        )
        _zoom.value = ZoomState(
            device.videoZoomFactor.toFloat(),
            device.minVideoZoomFactor.toFloat(),
            device.maxAvailableVideoZoomFactor.toFloat(),
        )
    }

    /** [device] as the enumeration lists it, or described from the device when the enumeration does not. */
    private fun lensInfo(device: CaptureDeviceOps): LensInfo =
        capabilities.value.allLenses.firstOrNull { it.id == device.uniqueId } ?: LensInfo(
            id = device.uniqueId,
            deviceType = CameraDeviceType.DEFAULT,
            lens = device.lens(),
            minZoom = 1f,
            maxZoom = device.maxAvailableVideoZoomFactor.toFloat(),
            hasFlash = false,
            isLogical = false,
        )

    private fun errorFor(failure: CustomCameraController.CameraException, lens: CameraLens?): CameraKError {
        val platform = (failure.cause as? NSErrorException)?.error
        return when {
            platform != null -> cameraKErrorFor(platform)
            failure is CustomCameraController.CameraException.DeviceNotAvailable -> CameraKError.NoCamera(lens, failure)
            else -> CameraKError.Hardware(null, failure)
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    private enum class Phase { CLOSED, STARTING, LIVE, FAILED }

    private class PendingStart(val generation: Int, val continuation: CancellableContinuation<CameraKResult<Unit>>)

    private class PendingSwitch(val interval: AutoCloseable, val input: AVCaptureInput)

    private companion object {
        const val TAG = "CameraK"
        const val MINIMUM_IOS = 16
        const val ROTATION_COORDINATOR_IOS = 17
        const val PAUSE_RESUME_IOS = 18
        const val DEFERRED_START_IOS = 26
        const val DEGREES_90 = 90
        const val DEGREES_180 = 180
        const val DEGREES_270 = 270
        val CLOSED = CameraKResult.Failure(CameraKError.Closed)

        fun failure(error: CameraKError) = CameraKResult.Failure(error)
    }
}

private fun CaptureDeviceOps.lens(): CameraLens = if (isFront) CameraLens.FRONT else CameraLens.BACK

private fun CameraSelection.lens(): CameraLens? = when (this) {
    is CameraSelection.ByLens -> lens
    is CameraSelection.Default -> CameraLens.BACK
    is CameraSelection.ById -> null
}

private fun CameraSelection.deviceType(): CameraDeviceType = when (this) {
    is CameraSelection.ByLens -> deviceType
    is CameraSelection.Default -> deviceType
    is CameraSelection.ById -> CameraDeviceType.DEFAULT
}

internal fun FlashMode.toAVCaptureFlashMode() = when (this) {
    FlashMode.ON -> AVCaptureFlashModeOn
    FlashMode.OFF -> AVCaptureFlashModeOff
    FlashMode.AUTO -> AVCaptureFlashModeAuto
}

private fun AVCaptureFlashMode.toFlashMode(): FlashMode = when (this) {
    AVCaptureFlashModeOn -> FlashMode.ON
    AVCaptureFlashModeAuto -> FlashMode.AUTO
    else -> FlashMode.OFF
}

internal fun TorchMode.toAVCaptureTorchMode(): AVCaptureTorchMode = when (this) {
    TorchMode.ON -> AVCaptureTorchModeOn
    TorchMode.OFF -> AVCaptureTorchModeOff
    TorchMode.AUTO -> AVCaptureTorchModeAuto
}

/** Whether the main bundle's Info.plist has `key`. */
private fun hasInfoPlistKey(key: String): Boolean = NSBundle.mainBundle.objectForInfoDictionaryKey(key) != null

/** UIDevice's orientation notifications, begun or ended on main, where UIKit wants them. */
private fun generateDeviceOrientationNotifications(on: Boolean) {
    dispatch_async(dispatch_get_main_queue()) {
        if (on) {
            UIDevice.currentDevice.beginGeneratingDeviceOrientationNotifications()
        } else {
            UIDevice.currentDevice.endGeneratingDeviceOrientationNotifications()
        }
    }
}

private fun runningIosMajorVersion(): Int =
    NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion.toInt() }

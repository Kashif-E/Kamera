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
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.error.InterruptionReason
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
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.AVFoundation.AVCaptureInput
import platform.AVFoundation.AVCaptureSessionDidStartRunningNotification
import platform.AVFoundation.AVCaptureSessionErrorKey
import platform.AVFoundation.AVCaptureSessionInterruptionEndedNotification
import platform.AVFoundation.AVCaptureSessionInterruptionReasonKey
import platform.AVFoundation.AVCaptureSessionRuntimeErrorNotification
import platform.AVFoundation.AVCaptureSessionWasInterruptedNotification
import platform.AVFoundation.AVCaptureVideoOrientation
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.CoreGraphics.CGRect
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIInterfaceOrientation
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
    readDeviceOrientation: () -> UIDeviceOrientation = { UIDevice.currentDevice.orientation },
    /** Begins (true) or ends (false) UIDevice's orientation notifications; tests count the calls. */
    generateOrientationNotifications: (Boolean) -> Unit = ::generateDeviceOrientationNotifications,
    /** Whether the app's Info.plist has a key: a test bundle's cannot change per test. */
    private val hasUsageDescription: (key: String) -> Boolean = ::hasInfoPlistKey,
    /** The rotation coordinators of iOS 17 and later; tests answer for the simulator, which has no camera. */
    rotationSources: RotationSourceFactory = CoordinatorRotationSources,
    /** Locks analysis frames' pixel buffers; tests count the locks. */
    private val pixelBufferLocker: PixelBufferLocker = CoreVideoPixelBufferLocker,
    private val enumerateLenses: () -> List<LensInfo> = custom::getCameraCapabilities,
) : CameraEngine {
    private val osVersion: Int = environment.runtime.osVersion ?: runningIosMajorVersion()
    private val tracer = environment.runtime.tracer ?: signpostTracer()

    /** The failure every call returns below iOS 16; null from 16 on. */
    private val unsupported: CameraKError? =
        if (osVersion < MINIMUM_IOS) CameraKError.Unsupported(CameraFeature.PLATFORM_VERSION) else null

    private val lock: EngineLock = EngineLock()

    private val generations: EngineGenerations =
        EngineGenerations(lock, tracer, custom, unsupported, frameOutputAttached = { frameSource.outputAttached })
    override val state: StateFlow<EngineState> = generations.state

    private val capabilitySource: EngineCapabilities =
        EngineCapabilities(lock, generations, custom, osVersion, enumerateLenses)
    override val capabilities: StateFlow<CameraCapabilities> get() = capabilitySource.capabilities

    private val rotation: EngineRotation = EngineRotation(
        lock = lock,
        generations = generations,
        custom = custom,
        osVersion = osVersion,
        readDeviceOrientation = readDeviceOrientation,
        generateOrientationNotifications = generateOrientationNotifications,
        rotationSources = rotationSources,
        boundDevice = { controls.observedDevice },
    )
    override val deviceOrientation: StateFlow<DeviceOrientation> = rotation.deviceOrientation
    internal val targetOrientation: DeviceOrientation? get() = rotation.targetOrientation

    private val controls: EngineControls =
        EngineControls(generations, capabilitySource, custom, targetOrientation = { rotation.targetOrientation })
    override val hardware: StateFlow<HardwareState> = controls.hardware
    override val zoom: StateFlow<ZoomState> = controls.zoom
    internal val desiredZoomRatio: Float get() = controls.desiredZoom
    internal val desiredTorchMode: TorchMode get() = controls.desiredTorch

    /** Recordings on the movie file output. */
    internal val recorder = AppleRecorder(
        custom = custom,
        cameraConfig = config,
        osVersion = osVersion,
        hasUsageDescription = hasUsageDescription,
        supportedQualities = capabilitySource::boundQualities,
        videoRotation = rotation::captureRotation,
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
    private val frameSource: AppleFrameSource = AppleFrameSource(
        custom = custom,
        keepOutput = config.frameAnalysis,
        recordingActive = { recorder.isActive },
        rotationDegrees = rotation::frameRotationDegrees,
        onSampleBuffer = generations::onSampleBuffer,
        locker = pixelBufferLocker,
    )
    override val frames: FrameSource = frameSource

    private val scope = CoroutineScope(
        SupervisorJob(environment.parentJob) + environment.dispatchers.default +
            CoroutineExceptionHandler { _, e -> CameraKLogger.e(TAG, "an iOS engine task failed", e) },
    )

    /** The reason of the latest interruption, recorded when AVFoundation posts it, before the hop to the queue. */
    private val lastInterruption = atomic<InterruptionReason?>(null)

    private val closeDone = Job()
    private val starts = Mutex()

    private val stills: EngineStills = EngineStills(lock, generations, custom, config, tracer, environment.dispatchers)

    /** Test seam: runs on the session queue between a lens switch's live check and its commit. */
    @Volatile
    internal var afterSwitchLiveCheck: () -> Unit = {}

    /** The session, made now so the preview has it before the first start; null below iOS 16. */
    private val session: CaptureSessionOps? = if (unsupported == null) custom.prepareSession() else null

    private val center = NSNotificationCenter.defaultCenter
    private val observers: List<Any> = observeSession()

    init {
        custom.captureOrientation = rotation::captureVideoOrientation
        custom.captureRotation = rotation::captureRotation
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
            scope.launch { capabilitySource.enumerate() }
        } else {
            capabilitySource.enumerated.complete(Unit)
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
            rotation.adoptPreviewLayer(layer)
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
        rotation.turnPreview(layer, interfaceOrientation?.toVideoOrientation())
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────────────────────────

    override suspend fun start(desired: DesiredHardware): CameraKResult<Unit> {
        if (generations.isClosed) return ENGINE_CLOSED
        unsupported?.let { error ->
            generations.failUnusable(error)
            return engineFailure(error)
        }
        if (config.analysisFormat != FrameFormat.YUV_420) {
            // AVCaptureVideoDataOutput delivers CameraK's frames in 420f only (design §6.1, §8).
            val error = CameraKError.Unsupported(CameraFeature.ANALYSIS_FORMAT)
            CameraKLogger.e(TAG, "iOS analysis frames are YUV_420; ${config.analysisFormat} fails the start")
            generations.failUnusable(error)
            return engineFailure(error)
        }
        capabilitySource.enumerated.await()
        return starts.withLock {
            when {
                generations.isClosed -> ENGINE_CLOSED
                generations.isLive -> CameraKResult.Success(Unit)
                else -> {
                    rotation.startObservingOrientation()
                    val started = tracer.beginInterval(TraceNames.START).use { openGeneration(desired) }
                    // Observed between a start and a stop: a start that failed has nothing to stop.
                    if (started is CameraKResult.Failure) rotation.stopObservingOrientation()
                    started
                }
            }
        }
    }

    private suspend fun openGeneration(desired: DesiredHardware): CameraKResult<Unit> =
        suspendCancellableCoroutine { continuation ->
            val generation = generations.open(continuation)
            if (generation == null) {
                continuation.resume(ENGINE_CLOSED)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { cancelStart(generation) }
            custom.afterQueuedSessionWork {
                platformCallback("starting the session") { startOnQueue(generation, desired) }
            }
        }

    /** Session queue: configures the session or moves it to the selected camera, then starts it. */
    private fun startOnQueue(generation: Int, desired: DesiredHardware) {
        if (!generations.isCurrent(generation, EnginePhase.STARTING)) return
        val selection = desired.selection
        val device = resolve(selection)
            ?: return generations.fail(generation, CameraKError.NoCamera(selection.lens()))
        val failure = when {
            !custom.isConfigured -> custom.configureOnQueue(device, device.lens(), selection.deviceType())
            device !== custom.currentDevice -> custom.useDeviceOnQueue(device)
            else -> null
        }
        if (failure != null) return generations.fail(generation, engineErrorFor(failure, selection.lens()))
        rotation.targetOrientation = desired.targetOrientation
        controls.takeDesired(desired)
        bindHardware(device)
        // A stop or close since the block was queued wins: the session is not started for nobody.
        if (!generations.isCurrent(generation, EnginePhase.STARTING)) return
        // Starting the session applies the torch; one already running keeps the torch it had.
        if (session?.isRunning == true) custom.reapplyTorchOnQueue() else custom.startRunningOnQueue()
        controls.applyDesiredZoom(device)
        controls.publishHardware()
        confirm(generation)
    }

    /** Session queue: Open when the session runs, Interrupted when the system holds it; otherwise waits. */
    private fun confirm(generation: Int) {
        val ops = session ?: return
        val interrupted = ops.isInterrupted
        val running = ops.isRunning
        when {
            interrupted -> generations.becomeLive(
                generation,
                EngineState.Interrupted(lastInterruption.value ?: InterruptionReason.OTHER),
            )
            running -> generations.becomeLive(generation, EngineState.Open)
        }
    }

    private fun cancelStart(generation: Int) {
        if (generations.cancelStart(generation)) {
            rotation.stopObservingOrientation()
            custom.afterQueuedSessionWork {
                platformCallback("stopping a cancelled start") { custom.stopRunningOnQueue() }
            }
        }
    }

    override suspend fun stop() {
        if (unsupported != null) {
            generations.closeUnusable()
            return
        }
        // A recording is finalized before the camera closes (contract rule 7).
        if (!generations.isClosed) recorder.finishForStop()
        val cut = generations.stop() ?: return
        cut.start?.continuation?.resume(ENGINE_CLOSED)
        rotation.stopObservingOrientation()
        suspendCancellableCoroutine { continuation ->
            custom.afterQueuedSessionWork {
                platformCallback("releasing a prepared recording") { recorder.releasePreparedOnQueue() }
                platformCallback("stopping the session") { custom.stopRunningOnQueue() }
                platformCallback("closing the rotation coordinator") { rotation.unbindOnQueue() }
                continuation.resume(Unit)
            }
        }
    }

    override fun close() {
        val cut = generations.close() ?: return
        cut.start?.continuation?.resume(ENGINE_CLOSED)
        stills.closeAll()
        observers.forEach(center::removeObserver)
        rotation.stopObservingOrientation()
        capabilitySource.enumerated.complete(Unit)
        if (session != null) {
            // A recording is finalized before the session is released (contract rule 7).
            recorder.finishForClose {
                custom.cleanupSession()
                custom.afterQueuedSessionWork {
                    controls.stopObservingHardware()
                    rotation.unbindOnQueue()
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
                    val generation = generations.current
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
                    val generation = generations.current
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
        if (generations.isLiveOrStarting(
                generation,
            )
        ) {
            generations.becomeLive(generation, EngineState.Interrupted(reason))
        }
    }

    private fun onInterruptionEnded(generation: Int) {
        if (!generations.isLiveOrStarting(generation)) return
        if (session?.isRunning == true) generations.becomeLive(generation, EngineState.Open)
    }

    private fun onDidStartRunning(generation: Int) {
        if (generations.isStopped(generation)) {
            // An open that arrives after the start it belonged to was stopped: the camera stays closed.
            custom.stopRunningOnQueue()
            return
        }
        if (generations.isLiveOrStarting(generation)) confirm(generation)
    }

    private fun onRuntimeError(generation: Int, error: NSError?) {
        if (!generations.isLiveOrStarting(generation)) return
        if (error == null) {
            CameraKLogger.e(TAG, "the session reported a runtime error without an NSError")
            return generations.fail(generation, CameraKError.Hardware(null))
        }
        val next = sessionErrorState(error)
        if (next is EngineState.Failed) return generations.fail(generation, next.error)
        val hardware = CameraKError.Hardware(error.code.toInt(), NSErrorException(error))
        // A reset restarts the session once; a second failure in a row, before the restart ran, ends the generation.
        val restart = generations.claimResetRestart() ?: return generations.fail(generation, hardware)
        generations.becomeLive(generation, next)
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
                    val pending = generations.isResetRestartPending(restart)
                    val ops = session
                    if (pending &&
                        ops != null &&
                        !ops.isRunning &&
                        !ops.isInterrupted
                    ) {
                        generations.fail(generation, error)
                    }
                }
            }
        }
    }

    // ─── Commands ───────────────────────────────────────────────────────────────────────────────

    override suspend fun selectCamera(selection: CameraSelection): CameraKResult<LensInfo> {
        generations.unusable()?.let { return it }
        capabilitySource.enumerated.await()
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
        generations.endPendingSwitch()
        val interval = tracer.beginInterval(TraceNames.LENS_SWITCH)
        custom.afterQueuedSessionWork {
            val generation = generations.current
            val result = try {
                switchOnQueue(selection, generation)
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "switching the camera failed", e)
                engineFailure(CameraKError.Internal(e))
            }
            // The input whose first sample buffer ends the interval, when a frame output is attached.
            val awaited = custom.currentVideoInput
                .takeIf { result is CameraKResult.Success && frameSource.outputAttached }
            // The phase and generation are checked under the lock that publishes the switch, so a stop
            // since the live check ends the switch here instead of leaving it waiting.
            val pending = awaited != null && generations.awaitSwitchFrame(generation, interval, awaited)
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
        generations.unusable()?.let { return it }
        if (!generations.isCurrent(generation, EnginePhase.LIVE)) return engineFailure(CameraKError.NotReady)
        afterSwitchLiveCheck()
        val device = resolve(selection) ?: return engineFailure(CameraKError.NoCamera(selection.lens()))
        if (custom.recordingInProgress()) return engineFailure(CameraKError.Busy(CameraOperation.LENS))
        // A refused input puts the previous camera back, inside the same transaction.
        custom.useDeviceOnQueue(device)?.let { return engineFailure(engineErrorFor(it, selection.lens())) }
        bindHardware(device)
        controls.applyDesiredZoom(device)
        controls.publishHardware()
        return CameraKResult.Success(capabilitySource.lensInfo(device))
    }

    override suspend fun setFlashMode(mode: FlashMode): CameraKResult<FlashMode> = controls.setFlashMode(mode)

    override suspend fun capture(): CameraKResult<CapturedImage> = stills.capture()

    override suspend fun setZoom(ratio: Float): CameraKResult<ZoomState> = controls.setZoom(ratio)

    override suspend fun setTorchMode(mode: TorchMode): CameraKResult<TorchMode> = controls.setTorchMode(mode)

    /**
     * Stores the target in any state before close; while a camera is bound it is reported in
     * [hardware] before this returns. The next start sets it from its desired hardware.
     */
    override suspend fun setTargetOrientation(orientation: DeviceOrientation?): CameraKResult<Unit> {
        generations.unusable()?.let { return it }
        rotation.targetOrientation = orientation
        return generations.onSessionQueue("setting the target orientation") {
            controls.publishHardware()
            CameraKResult.Success(Unit)
        }
    }

    /** Adds the movie output (and the microphone, for audio) now, so the next start commits nothing. */
    override suspend fun prepareRecording(config: VideoConfiguration): CameraKResult<Unit> {
        generations.notOpen()?.let { return it }
        return recorder.prepare(config)
    }

    /** Returns once AVFoundation reports the recording started; its value is the file's path. */
    override suspend fun startRecording(config: VideoConfiguration): CameraKResult<String> {
        generations.notOpen()?.let { return it }
        return recorder.start(config)
    }

    /** Reaches the movie output in any state before close, so a recording can always be ended. */
    override suspend fun stopRecording(): CameraKResult<RecordedVideo> {
        generations.unusable()?.let { return it }
        return recorder.stop(RecordingEndReason.USER)
    }

    override suspend fun pauseRecording(): CameraKResult<Unit> {
        generations.unusable()?.let { return it }
        return recorder.pause()
    }

    override suspend fun resumeRecording(): CameraKResult<Unit> {
        generations.unusable()?.let { return it }
        return recorder.resume()
    }

    /** The orientation captures and movies take: the target when one is set, otherwise the device's. */
    internal fun captureVideoOrientation(): AVCaptureVideoOrientation = rotation.captureVideoOrientation()

    /**
     * How photos and movies are turned (design §2.4): the target orientation when one is set, else the
     * rotation coordinator's horizon-level capture angle from iOS 17, else the device's orientation.
     */
    internal fun captureRotation(): VideoRotation = rotation.captureRotation()

    // ─── Platform reads ─────────────────────────────────────────────────────────────────────────

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
        capabilitySource.bind(device)
        controls.observe(device)
        rotation.bindOnQueue()
    }

    private companion object {
        const val TAG = "CameraK"
        const val MINIMUM_IOS = 16
        const val DEFERRED_START_IOS = 26
    }
}

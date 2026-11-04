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
import com.kashif.cameraK.engine.InactiveFrameSource
import com.kashif.cameraK.engine.LegacyControllerAccess
import com.kashif.cameraK.engine.PlatformPreviewSource
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
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.START_TIMEOUT
import com.kashif.cameraK.state.ZoomState
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.AVFoundation.AVCaptureFlashMode
import platform.AVFoundation.AVCaptureFlashModeAuto
import platform.AVFoundation.AVCaptureFlashModeOff
import platform.AVFoundation.AVCaptureFlashModeOn
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
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.Foundation.NSError
import platform.Foundation.NSLock
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIDeviceOrientationDidChangeNotification
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
 *   close, which the facade's captures and movies read.
 * - The device orientation is observed from UIDeviceOrientationDidChangeNotification between start
 *   and stop.
 *
 * Until I3', `capture` and the recording calls return `InvalidState`; the 1.x facade
 * ([legacyController]) still does them on the session this engine owns.
 */
internal class AppleCameraEngine(
    private val config: CameraConfiguration,
    environment: CameraKEnvironment,
    internal val custom: CustomCameraController,
    /** How the device is held now; tests answer for the simulator, which cannot be turned. */
    private val readDeviceOrientation: () -> UIDeviceOrientation = { UIDevice.currentDevice.orientation },
    private val enumerateLenses: () -> List<LensInfo> = custom::getCameraCapabilities,
) : CameraEngine,
    LegacyControllerAccess {
    private val osVersion: Int = environment.runtime.osVersion ?: runningIosMajorVersion()
    private val tracer = environment.runtime.tracer ?: signpostTracer()

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

    /** The facade's orientation listener, called with each new device orientation. */
    private val orientationListener = atomic<((DeviceOrientation) -> Unit)?>(null)

    /** The zoom ratio the engine re-applies after each start and camera switch. */
    @Volatile
    private var desiredZoom = 1f

    private val _capabilities = MutableStateFlow(CameraCapabilities.EMPTY)
    override val capabilities: StateFlow<CameraCapabilities> = _capabilities.asStateFlow()

    override val recordingEvents: Flow<EngineRecordingEvent> = emptyFlow()
    override val frames: FrameSource = InactiveFrameSource()

    private val scope = CoroutineScope(
        SupervisorJob(environment.parentJob) + environment.dispatchers.default +
            CoroutineExceptionHandler { _, e -> CameraKLogger.e(TAG, "CameraK: an iOS engine task failed", e) },
    )

    // Everything below the lock is read and written only while holding it.
    private val lock = NSLock()
    private var generation = 0
    private var phase = Phase.CLOSED
    private var closed = false
    private var pendingStart: PendingStart? = null
    private var firstFrame: AutoCloseable? = null

    /** Set by a media-services reset's restart, cleared when the session runs again. */
    private var restartedAfterReset = false
    private var resetRestarts = 0
    private var orientationObserver: Any? = null
    private var enumeratedLenses: List<LensInfo> = emptyList()
    private var torchModesById: Map<String, Set<TorchMode>> = emptyMap()

    /** Each bound camera's flash modes, as the photo output reported them with that camera behind it. */
    private val boundFlashModes = mutableMapOf<String, Set<FlashMode>>()

    // Session queue only: the device whose torch and zoom the engine observes, and the observation.
    private var observedDevice: CaptureDeviceOps? = null
    private var hardwareObservation: AutoCloseable? = null

    /** The reason of the latest interruption, recorded when AVFoundation posts it, before the hop to the queue. */
    private val lastInterruption = atomic<InterruptionReason?>(null)

    private val enumerated = CompletableDeferred<Unit>()
    private val closeDone = Job()
    private val starts = Mutex()
    private val facadeCalls = Mutex()
    private val facade = atomic<CameraController?>(null)
    private val refusedOperations = atomic(emptySet<CameraOperation>())

    /** The session, made now so the preview has it before the first start; null below iOS 16. */
    private val session: CaptureSessionOps? = if (unsupported == null) custom.prepareSession() else null

    private val center = NSNotificationCenter.defaultCenter
    private val observers: List<Any> = observeSession()

    init {
        custom.captureOrientation = ::captureVideoOrientation
        if (unsupported == null) {
            scope.launch { enumerate() }
        } else {
            enumerated.complete(Unit)
            closeDone.complete()
        }
    }

    override val preview: PlatformPreviewSource = object : PlatformPreviewSource {
        override fun createPreviewLayer(): AVCaptureVideoPreviewLayer {
            val avSession = session?.avSession
            val layer = avSession?.let { AVCaptureVideoPreviewLayer(session = it) } ?: AVCaptureVideoPreviewLayer()
            // ResizeAspect (letterbox), so the preview shows the frame that gets captured (#119).
            layer.videoGravity = AVLayerVideoGravityResizeAspect
            custom.adoptPreviewLayer(layer)
            return layer
        }
    }

    override val legacyController: CameraController
        get() = facade.value ?: CameraController(this, config).let { made ->
            if (facade.compareAndSet(null, made)) made else facade.value!!
        }

    /** Makes [controller], a facade an app built directly, the one this engine carries. */
    internal fun adopt(controller: CameraController) {
        facade.compareAndSet(null, controller)
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────────────────────────

    override suspend fun start(desired: DesiredHardware): CameraKResult<Unit> {
        if (isClosed) return CLOSED
        unsupported?.let { error ->
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
        custom.setFlashMode(desired.flashMode.toAVCaptureFlashMode())
        custom.torchMode = desired.torchMode.toAVCaptureTorchMode()
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
        val cut = locked {
            if (closed) return
            generation++
            phase = Phase.CLOSED
            endFirstFrame()
            _state.value = EngineState.Closed
            pendingStart.also { pendingStart = null }
        }
        cut?.continuation?.resume(CLOSED)
        stopObservingOrientation()
        suspendCancellableCoroutine { continuation ->
            custom.afterQueuedSessionWork {
                platformCallback("stopping the session") { custom.stopRunningOnQueue() }
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
            _state.value = EngineState.Closed
            pendingStart.also { pendingStart = null }
        }
        cut?.continuation?.resume(CLOSED)
        observers.forEach(center::removeObserver)
        stopObservingOrientation()
        orientationListener.value = null
        enumerated.complete(Unit)
        facade.value?.releaseForEngineClose()
        if (session != null) {
            custom.cleanupSession()
            custom.afterQueuedSessionWork {
                stopObservingHardware()
                closeDone.complete()
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
            CameraKLogger.e(TAG, "CameraK: the session reported a runtime error without an NSError")
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
            CameraKLogger.e(TAG, "CameraK: handling $what failed", e)
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
                endFirstFrame()
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
            _state.value = EngineState.Failed(error)
            pendingStart.also { pendingStart = null }
        }
        CameraKLogger.e(TAG, "CameraK: the camera session failed", error.cause)
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
     * `CameraK.lensSwitch` interval, which ends when the swap has committed (the session keeps
     * running through it) or, marked failed, when the switch was refused.
     */
    internal fun switchCamera(selection: CameraSelection, done: (CameraKResult<LensInfo>) -> Unit) {
        val interval = tracer.beginInterval(TraceNames.LENS_SWITCH)
        custom.afterQueuedSessionWork {
            val result = try {
                switchOnQueue(selection)
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "CameraK: switching the camera failed", e)
                failure(CameraKError.Internal(e))
            }
            if (result is CameraKResult.Failure) tracer.marker(AppleTraceNames.LENS_SWITCH_FAILED)
            interval.close()
            done(result)
        }
    }

    private fun switchOnQueue(selection: CameraSelection): CameraKResult<LensInfo> {
        unusable()?.let { return it }
        if (!locked { phase == Phase.LIVE }) return failure(CameraKError.NotReady)
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

    override suspend fun capture(): CameraKResult<CapturedImage> = refuse(CameraOperation.CAPTURE)

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

    override suspend fun prepareRecording(config: VideoConfiguration): CameraKResult<Unit> =
        refuse(CameraOperation.RECORD_PREPARE)

    override suspend fun startRecording(config: VideoConfiguration): CameraKResult<String> =
        refuse(CameraOperation.RECORD_START)

    override suspend fun stopRecording(): CameraKResult<RecordedVideo> = refuse(CameraOperation.RECORD_STOP)

    override suspend fun pauseRecording(): CameraKResult<Unit> = refuse(CameraOperation.RECORD_PAUSE)

    override suspend fun resumeRecording(): CameraKResult<Unit> = refuse(CameraOperation.RECORD_RESUME)

    // I3' gives these their paths; until then each kind is logged once, as LegacyControllerEngine does.
    private fun refuse(operation: CameraOperation): CameraKResult<Nothing> {
        unusable()?.let { return it }
        if (operation !in refusedOperations.getAndUpdate { it + operation }) {
            CameraKLogger.w(TAG, "CameraK: the iOS engine does not run $operation yet; issue it on Ready.controller")
        }
        return failure(CameraKError.InvalidState(operation))
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
                    CameraKLogger.e(TAG, "CameraK: $what failed", e)
                    failure(CameraKError.Internal(e))
                }
                continuation.resume(result)
            }
        }

    private fun hardwareFailure(what: String, cause: CustomCameraController.CameraException): CameraKResult.Failure {
        CameraKLogger.e(TAG, "CameraK: $what failed", cause)
        return failure(errorFor(cause, null))
    }

    // ─── The facade's session calls ─────────────────────────────────────────────────────────────

    /** The facade's startSession: starts after any facade call made before it. */
    internal fun launchStart(desired: DesiredHardware) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            facadeCalls.withLock {
                val result = start(desired)
                if (result is CameraKResult.Failure) {
                    CameraKLogger.e(TAG, "CameraK: starting the camera session failed", result.error.cause)
                }
            }
        }
    }

    /** The facade's stopSession: stops after any facade call made before it. */
    internal fun launchStop() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { facadeCalls.withLock { stop() } }
    }

    /**
     * A facade setter, after any facade call made before it: [command] when the camera is Open then,
     * else [whenNotOpen]. A failure is logged, as the 1.x setters, which return nothing, did.
     */
    internal fun launchFacadeCommand(
        what: String,
        whenNotOpen: () -> Unit = {},
        command: suspend AppleCameraEngine.() -> CameraKResult<*>,
    ) {
        if (isClosed) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            facadeCalls.withLock {
                if (_state.value != EngineState.Open) return@withLock whenNotOpen()
                val result = command()
                if (result is CameraKResult.Failure) {
                    CameraKLogger.e(TAG, "CameraK: $what failed: ${result.error::class.simpleName}", result.error.cause)
                }
            }
        }
    }

    /**
     * The flash mode the 1.x facade keeps for [mode]: OFF when the Open camera does not support it, as
     * 1.x's setFlashMode stored for a camera without a flash; [mode] while none is Open.
     */
    internal fun facadeFlashMode(mode: FlashMode): FlashMode {
        if (_state.value != EngineState.Open) return mode
        val camera = custom.currentDevice?.let(::lensInfo) ?: return mode
        if (mode in capabilities.value.supportedFlashModes(camera.id)) return mode
        CameraKLogger.w(TAG, "CameraK: the camera does not support flash mode $mode; using OFF")
        return FlashMode.OFF
    }

    /** The facade's target orientation: stored in any state, as 1.x did, and reported while a camera is bound. */
    internal fun storeTargetOrientation(orientation: DeviceOrientation?) {
        target.value = orientation
        custom.afterQueuedSessionWork {
            platformCallback("reporting the target orientation") { publishHardware() }
        }
    }

    /** The facade's orientation listener; null removes it. */
    internal fun setDeviceOrientationListener(listener: ((DeviceOrientation) -> Unit)?) {
        orientationListener.value = listener
    }

    /** The orientation captures and movies take: the target when one is set, otherwise the device's. */
    internal fun captureVideoOrientation(): AVCaptureVideoOrientation =
        target.value?.toVideoOrientation() ?: custom.currentVideoOrientation()

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
        dispatch_async(dispatch_get_main_queue()) {
            UIDevice.currentDevice.beginGeneratingDeviceOrientationNotifications()
        }
        platformCallback("reading the device orientation") { updateDeviceOrientation() }
    }

    private fun stopObservingOrientation() {
        val observer = locked { orientationObserver.also { orientationObserver = null } } ?: return
        center.removeObserver(observer)
        dispatch_async(dispatch_get_main_queue()) {
            UIDevice.currentDevice.endGeneratingDeviceOrientationNotifications()
        }
    }

    /** Face up, face down and unknown name no orientation, so they keep the last one. */
    private fun updateDeviceOrientation() {
        val orientation = readDeviceOrientation().toDeviceOrientation() ?: return
        val changed = locked {
            if (closed || _deviceOrientation.value == orientation) return@locked false
            _deviceOrientation.value = orientation
            true
        }
        if (changed) orientationListener.value?.invoke(orientation)
    }

    // ─── Platform reads ─────────────────────────────────────────────────────────────────────────

    private suspend fun enumerate() {
        val (lenses, torchModes) = try {
            val lenses = enumerateLenses()
            lenses to lenses.associate { it.id to torchModesOf(it) }
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "CameraK: enumerating the cameras failed; reporting none", e)
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
            capabilitiesOf(enumeratedLenses, torchModesById, boundFlashModes.toMap())
        }
        _capabilities.value = capabilities
    }

    /**
     * A lens's flash modes are the photo output's once the lens has been bound, and come from its
     * hasFlash before; its torch modes are its device's.
     */
    private fun capabilitiesOf(
        lenses: List<LensInfo>,
        torchModes: Map<String, Set<TorchMode>>,
        boundFlash: Map<String, Set<FlashMode>>,
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
        supportedVideoQualities = emptySet(),
        supportsVideoBitrate = false,
        supportedAspectRatios = setOf(AspectRatio.RATIO_4_3, AspectRatio.RATIO_16_9, AspectRatio.RATIO_9_16),
        supportedImageFormats = setOf(ImageFormat.JPEG),
        supportedAnalysisFormats = emptySet(),
        supportsSquareCapture = false,
    )

    private fun resolve(selection: CameraSelection): CaptureDeviceOps? = when (selection) {
        is CameraSelection.ById -> custom.findCameraById(selection.id)
        is CameraSelection.ByLens -> custom.findCamera(selection.lens, selection.deviceType)
    }

    /**
     * Session queue, after [device] became the session's camera: its flash modes from the photo output
     * now behind it, and the device's torch and zoom observed in place of the previous camera's.
     */
    private fun bindHardware(device: CaptureDeviceOps) {
        val flash = custom.captureFlashModes.map { it.toFlashMode() }.toSet() + FlashMode.OFF
        locked { boundFlashModes[device.uniqueId] = flash }
        publishCapabilities()
        if (observedDevice === device) return
        stopObservingHardware()
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
            CameraKLogger.e(TAG, "CameraK: re-applying the zoom failed", failure)
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

    private companion object {
        const val TAG = "CameraK"
        const val MINIMUM_IOS = 16
        const val PAUSE_RESUME_IOS = 18
        val CLOSED = CameraKResult.Failure(CameraKError.Closed)

        fun failure(error: CameraKError) = CameraKResult.Failure(error)
    }
}

private fun CaptureDeviceOps.lens(): CameraLens = if (isFront) CameraLens.FRONT else CameraLens.BACK

private fun CameraSelection.lens(): CameraLens? = (this as? CameraSelection.ByLens)?.lens

private fun CameraSelection.deviceType(): CameraDeviceType =
    (this as? CameraSelection.ByLens)?.deviceType ?: CameraDeviceType.DEFAULT

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

private fun runningIosMajorVersion(): Int =
    NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion.toInt() }

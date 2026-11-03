@file:OptIn(CameraKEngineApi::class)

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
import com.kashif.cameraK.engine.PreviewFrame
import com.kashif.cameraK.engine.TraceNames
import com.kashif.cameraK.engine.Tracer
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.ZoomState
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * No frame for this long after Open interrupts the session (§2.4 desktop). A webcam delivers a
 * frame every 33 ms at 30 fps and about every 200 ms in low light; five seconds is a camera that has
 * stopped, not a slow one.
 */
internal val NO_FRAME_TIMEOUT: Duration = 5.seconds

/**
 * An interrupted session whose camera has not delivered a frame again within this long is
 * Disconnected. Two reopens with the default backoff fit in it, each waiting [NO_FRAME_TIMEOUT].
 */
internal val RECOVERY_TIMEOUT: Duration = 10.seconds

/**
 * The desktop engine (§2.4): the single-producer grab loop of [CameraGrabber] behind the engine
 * contract. Open follows the first frame; a camera that stops delivering, or a `grab()` that
 * fails, is Interrupted(OTHER) while the engine reopens it with backoff, then Failed(Disconnected).
 * Open failures map through [JavaCvErrorMapper]. Every grab loop runs on its own worker thread from
 * the runtime, with `grab()` interruptible, so a stop or a cancelled start always reaches the camera.
 *
 * Each start is a generation, a [Session]; each grab loop of it a [Run]. A callback counts only for
 * the current run of the current session, so a late one from a stopped, closed or failed session
 * does nothing (§2.3 rules 1 to 3). State changes happen under [lock], on whichever thread reports
 * them; the engine's own coroutines (enumeration, watchdog, recovery) run on
 * `environment.dispatchers.default`.
 *
 * Until D2' and D3' there is one device, whose flash and torch are OFF and zoom fixed at 1: the
 * engine accepts `setFlashMode(OFF)` and refuses every other command with `InvalidState`, logging
 * the first refusal of each kind at warning (§12.1). Until C1' it carries the 1.x facade over
 * itself, [legacyController].
 */
internal class DesktopCameraEngine(
    private val config: CameraConfiguration,
    environment: CameraKEnvironment,
    private val source: GrabberSource,
    private val authorization: CameraAuthorization = MacCameraAuthorization(),
    private val os: OperatingSystem = currentOperatingSystem(),
    nanoClock: () -> Long = System::nanoTime,
    horizontalFlip: Boolean = false,
    private val listenerWrapper: (GrabLoopListener) -> GrabLoopListener = { it },
    private val enumerate: () -> CameraCapabilities = ::desktopCapabilities,
    facade: (DesktopCameraEngine) -> CameraController = { CameraController(it, config) },
) : CameraEngine,
    LegacyControllerAccess {
    private val runtime = environment.runtime
    private val tracer: Tracer = runtime.tracer ?: DesktopLogTracer

    /** The producer the facade's captures and recordings read their frames from. */
    internal val grabber: CameraGrabber =
        CameraGrabber(MutableSharedFlow(), { }, null, nanoClock).apply { setHorizontalFlip(horizontalFlip) }

    override val legacyController: CameraController by lazy { facade(this) }

    private val _state = MutableStateFlow<EngineState>(EngineState.Closed)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _hardware = MutableStateFlow(HardwareState(FlashMode.OFF, TorchMode.OFF, null, null))
    override val hardware: StateFlow<HardwareState> = _hardware.asStateFlow()

    private val _zoom = MutableStateFlow(ZoomState(1f, 1f, 1f))
    override val zoom: StateFlow<ZoomState> = _zoom.asStateFlow()

    // A desktop camera does not rotate with anything; the 1.x controller reported PORTRAIT.
    override val deviceOrientation: StateFlow<DeviceOrientation> =
        MutableStateFlow(DeviceOrientation.PORTRAIT).asStateFlow()

    private val _capabilities = MutableStateFlow(CameraCapabilities.EMPTY)
    override val capabilities: StateFlow<CameraCapabilities> = _capabilities.asStateFlow()

    override val recordingEvents: Flow<EngineRecordingEvent> = emptyFlow()
    override val frames: FrameSource = InactiveFrameSource()

    private val previewFrames = MutableStateFlow<PreviewFrame?>(null)
    override val preview: PlatformPreviewSource = object : PlatformPreviewSource {
        override val frames: StateFlow<PreviewFrame?> = previewFrames.asStateFlow()
    }

    private val scope = CoroutineScope(
        SupervisorJob(environment.parentJob) + environment.dispatchers.default +
            CoroutineExceptionHandler { _, e -> CameraKLogger.e(TAG, "A desktop camera engine task failed", e) },
    )

    // Guards every field below and every write of _state, _hardware and previewFrames.
    private val lock = Any()
    private var closed = false
    private var session: Session? = null
    private val liveRuns = mutableSetOf<Job>()

    private val enumeration = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { enumerate() }
    private val refused = atomic(emptySet<CameraOperation>())

    init {
        // On the engine's scope after construction, never in the constructor (§6.3).
        scope.launch { capabilitiesNow() }
    }

    override suspend fun start(desired: DesiredHardware): CameraKResult<Unit> {
        var fresh = false
        val current = synchronized(lock) {
            if (closed) return CameraKResult.Failure(CameraKError.Closed)
            val active = session?.takeUnless { it.ended }
            when {
                active == null -> Session(desired).also {
                    session = it
                    fresh = true
                    publishLocked(EngineState.Opening)
                }
                _state.value == EngineState.Open || _state.value is EngineState.Interrupted ->
                    return CameraKResult.Success(Unit)
                else -> active
            }
        }
        // A new generation is begun before the first suspension, so a stop that follows this call
        // on the same thread always finds it (the 1.x facade starts and stops from any thread).
        if (fresh) begin(current)
        return try {
            current.outcome.await()
        } catch (e: CancellationException) {
            // A cancelled start leaves the camera closed (§2.3 rule 2): its grab loop is cancelled and
            // releases the camera as it leaves grab(). It does not wait for that, so a canceller (the
            // holder's start timeout) is never held by a native call that ignores the interrupt.
            if (fresh) end(current)
            throw e
        }
    }

    private fun begin(session: Session) {
        tracer.marker(TraceNames.START)
        val device = select(capabilitiesNow(), session.desired.selection)
        synchronized(lock) {
            if (!isCurrent(session)) return
            if (device == null) {
                val lens = (session.desired.selection as? CameraSelection.ByLens)?.lens
                failLocked(session, CameraKError.NoCamera(lens))
                return
            }
            session.device = device
            launchRunLocked(session)
        }
    }

    // ById must name an enumerated device (§4.1 row 2). The one desktop device answers any lens
    // until D2' enumerates devices: a webcam faces no particular way, and 1.x opened it for both.
    private fun select(capabilities: CameraCapabilities, selection: CameraSelection): LensInfo? = when (selection) {
        is CameraSelection.ById -> capabilities.allLenses.firstOrNull { it.id == selection.id }
        is CameraSelection.ByLens -> capabilities.lenses(selection.lens).firstOrNull()
            ?: capabilities.allLenses.firstOrNull()
    }

    private fun launchRunLocked(session: Session): Run {
        session.run?.job?.cancel()
        val run = Run(session)
        session.run = run
        val listener = listenerWrapper(RunListener(run))
        val job = grabber.startRun(scope, source, checkNotNull(session.device), runtime.newWorker, listener)
        run.job = job
        liveRuns += job
        job.invokeOnCompletion { synchronized(lock) { liveRuns -= job } }
        return run
    }

    /** Ends the session, then waits (cancellably) until every grab loop released the camera and its lock. */
    override suspend fun stop() {
        end(null)?.joinAll()
    }

    // Ends [expected], or whichever session is current when it is null, and returns the grab loops
    // still to release the camera; null after close.
    private fun end(expected: Session?): List<Job>? = synchronized(lock) {
        if (closed) return null
        val current = session
        if (expected != null && current !== expected) return null
        if (current != null) {
            session = null
            endLocked(current)
            previewFrames.value = null
            _state.value = EngineState.Closed
        }
        liveRuns.toList()
    }

    private fun endLocked(session: Session) {
        session.ended = true
        session.watchdog?.cancel()
        session.recovery?.cancel()
        session.run?.job?.cancel()
        session.outcome.complete(CameraKResult.Failure(CameraKError.Closed))
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            session?.let(::endLocked)
            session = null
            previewFrames.value = null
            _state.value = EngineState.Closed
        }
        // Each grab loop still releases the camera in its own finally; closeAndJoin waits for that.
        scope.cancel()
    }

    override suspend fun closeAndJoin() {
        close()
        synchronized(lock) { liveRuns.toList() }.joinAll()
    }

    private fun onRunOpened(run: Run, relaxed: Boolean) = synchronized(lock) {
        val session = run.session
        if (!isCurrentRun(run)) return
        // The camera opened only at its own mode, and the configuration asked for a size (§4.1 row 3).
        if (relaxed && config.targetResolution != null) {
            failLocked(session, CameraKError.Unsupported(CameraFeature.TARGET_RESOLUTION))
        }
    }

    private fun onRunFrame(run: Run, image: BufferedImage, captureNanos: Long) = synchronized(lock) {
        val session = run.session
        if (!isCurrentRun(run)) return
        previewFrames.value = PreviewFrame(image, captureNanos)
        session.frames.incrementAndGet()
        if (_state.value == EngineState.Open) return
        // The first frame of this run: the platform confirmed the session (§2.3 rule 1).
        _hardware.value = HardwareState(FlashMode.OFF, TorchMode.OFF, session.device, null)
        _state.value = EngineState.Open
        session.outcome.complete(CameraKResult.Success(Unit))
        run.firstFrame.complete(true)
        startWatchdogLocked(session)
        if (!session.opened) {
            session.opened = true
            tracer.marker(TraceNames.FIRST_FRAME)
        }
    }

    private fun onRunFailure(run: Run, failure: Throwable) {
        // Before Open this is a failed open; the macOS authorization read may need JNA, so outside the lock.
        val beforeOpen = synchronized(lock) { isCurrentRun(run) && !run.session.opened }
        val openError = if (beforeOpen || JavaCvErrorMapper.isNativeLibraryMissing(failure)) {
            JavaCvErrorMapper.openFailure(failure, os) { authorization.cameraStatus() }
        } else {
            null
        }
        synchronized(lock) {
            val session = run.session
            if (!isCurrentRun(run)) return
            when {
                // Missing natives, and a camera that never opened: no reopen fixes either.
                openError != null && (openError is CameraKError.NativeLibraryMissing || !session.opened) ->
                    failLocked(session, openError)
                _state.value == EngineState.Open -> {
                    CameraKLogger.w(TAG, "The desktop camera stopped delivering frames; reopening it", failure)
                    interruptLocked(session)
                }
                // A reopen that failed: the recovery tries again until it gives up.
                else -> run.firstFrame.complete(false)
            }
        }
    }

    private fun interruptLocked(session: Session) {
        _state.value = EngineState.Interrupted(InterruptionReason.OTHER)
        session.watchdog?.cancel()
        session.run?.job?.cancel()
        session.recovery = scope.launch { recover(session) }
    }

    // Reopens the camera with backoff until a run delivers a frame (Open, from onRunFrame) or the
    // recovery timeout passes (§4.1 row 7).
    private suspend fun recover(session: Session) {
        withTimeoutOrNull(RECOVERY_TIMEOUT) { reopenUntilAFrame(session) } ?: synchronized(lock) {
            if (isCurrent(session) && _state.value is EngineState.Interrupted) {
                failLocked(session, CameraKError.Disconnected())
            }
        }
    }

    private suspend fun reopenUntilAFrame(session: Session) {
        var attempt = 1
        while (true) {
            delay(runtime.backoff.delayBefore(attempt++))
            val run = synchronized(lock) { if (isCurrent(session)) launchRunLocked(session) else null } ?: return
            if (withTimeoutOrNull(NO_FRAME_TIMEOUT) { run.firstFrame.await() } == true) return
            run.job?.cancel()
        }
    }

    private fun startWatchdogLocked(session: Session) {
        session.watchdog?.cancel()
        session.watchdog = scope.launch {
            while (true) {
                val seen = session.frames.get()
                delay(NO_FRAME_TIMEOUT)
                if (session.frames.get() == seen) {
                    synchronized(lock) {
                        if (isCurrent(session) && _state.value == EngineState.Open) {
                            CameraKLogger.w(TAG, "The desktop camera delivered no frame in time; reopening it")
                            interruptLocked(session)
                        }
                    }
                    return@launch
                }
            }
        }
    }

    // Failed ends the generation: the session's callbacks stop counting, its grab loop is cancelled
    // and releases the camera, and stop() publishes Closed after waiting for that (§2.3 rule 1).
    private fun failLocked(session: Session, error: CameraKError) {
        CameraKLogger.e(TAG, "The desktop camera session failed", error.cause)
        session.ended = true
        session.watchdog?.cancel()
        session.recovery?.cancel()
        session.run?.job?.cancel()
        _state.value = EngineState.Failed(error)
        session.outcome.complete(CameraKResult.Failure(error))
    }

    private fun isCurrent(session: Session): Boolean = !closed && this.session === session && !session.ended

    private fun isCurrentRun(run: Run): Boolean = isCurrent(run.session) && run.session.run === run

    // A state write after close leaves Closed.
    private fun publishLocked(next: EngineState) {
        _state.value = if (closed) EngineState.Closed else next
    }

    private fun capabilitiesNow(): CameraCapabilities = enumeration.value.also { _capabilities.value = it }

    override suspend fun setFlashMode(mode: FlashMode): CameraKResult<FlashMode> {
        if (isClosed()) return CameraKResult.Failure(CameraKError.Closed)
        // Flash OFF is what a desktop camera has: accepted, and nothing to change (K1's flash step).
        return if (mode == FlashMode.OFF) CameraKResult.Success(FlashMode.OFF) else refuse(CameraOperation.FLASH)
    }

    override suspend fun capture(): CameraKResult<CapturedImage> = refuse(CameraOperation.CAPTURE)

    override suspend fun setZoom(ratio: Float): CameraKResult<ZoomState> = refuse(CameraOperation.ZOOM)

    override suspend fun setTorchMode(mode: TorchMode): CameraKResult<TorchMode> = refuse(CameraOperation.TORCH)

    override suspend fun selectCamera(selection: CameraSelection): CameraKResult<LensInfo> =
        refuse(CameraOperation.LENS)

    override suspend fun setTargetOrientation(orientation: DeviceOrientation?): CameraKResult<Unit> =
        refuse(CameraOperation.ORIENTATION)

    override suspend fun prepareRecording(config: VideoConfiguration): CameraKResult<Unit> =
        refuse(CameraOperation.RECORD_PREPARE)

    override suspend fun startRecording(config: VideoConfiguration): CameraKResult<String> =
        refuse(CameraOperation.RECORD_START)

    override suspend fun stopRecording(): CameraKResult<RecordedVideo> = refuse(CameraOperation.RECORD_STOP)

    override suspend fun pauseRecording(): CameraKResult<Unit> = refuse(CameraOperation.RECORD_PAUSE)

    override suspend fun resumeRecording(): CameraKResult<Unit> = refuse(CameraOperation.RECORD_RESUME)

    private fun isClosed(): Boolean = synchronized(lock) { closed }

    // Until D2' and D3' give the engine these commands, the facade carries them (§12.1).
    private fun refuse(operation: CameraOperation): CameraKResult<Nothing> {
        if (isClosed()) return CameraKResult.Failure(CameraKError.Closed)
        if (operation !in refused.getAndUpdate { it + operation }) {
            CameraKLogger.w(TAG, "The desktop engine refuses the $operation command; issue it on Ready.controller")
        }
        return CameraKResult.Failure(CameraKError.InvalidState(operation))
    }

    /** One start: a generation (§2.3 rule 2). */
    private class Session(val desired: DesiredHardware) {
        val outcome = CompletableDeferred<CameraKResult<Unit>>()
        val frames = AtomicLong()
        var device: LensInfo? = null
        var run: Run? = null
        var watchdog: Job? = null
        var recovery: Job? = null
        var opened = false
        var ended = false
    }

    /** One grab loop of a session: the first, or a reopen while interrupted. */
    private class Run(val session: Session) {
        var job: Job? = null

        /** True at the run's first frame, false when it fails before one. */
        val firstFrame = CompletableDeferred<Boolean>()
    }

    private inner class RunListener(private val run: Run) : GrabLoopListener {
        override fun onOpened(relaxed: Boolean) = onRunOpened(run, relaxed)

        override fun onFrame(image: BufferedImage, captureNanos: Long) = onRunFrame(run, image, captureNanos)

        override fun onFailure(failure: Throwable, opened: Boolean) = onRunFailure(run, failure)
    }

    private companion object {
        const val TAG = "CameraK"
    }
}

/** Debug log markers: the desktop's platform tracer (§11.3). */
private object DesktopLogTracer : Tracer {
    override fun beginInterval(name: String): AutoCloseable {
        CameraKLogger.d("CameraK", "$name begins")
        return AutoCloseable { CameraKLogger.d("CameraK", "$name ends") }
    }

    override fun marker(name: String) = CameraKLogger.d("CameraK", name)
}

/** The 1.x enumerator's one camera (CameraController.desktop.kt's getCameraCapabilities). */
internal fun desktopLensInfo(): LensInfo = LensInfo(
    id = "",
    deviceType = CameraDeviceType.DEFAULT,
    lens = CameraLens.BACK,
    minZoom = 1f,
    maxZoom = 1f,
    hasFlash = false,
    isLogical = false,
)

/**
 * The 1.x enumerator's one camera with the first real values of §6.3: flash and torch OFF only
 * (from `hasFlash`), and pause and resume through RecordingClock. Everything else keeps the 1.x
 * constructor's values until D3' and D4' fill it.
 */
internal fun desktopCapabilities(): CameraCapabilities {
    val legacy = CameraCapabilities(listOf(desktopLensInfo()))
    val ids = legacy.allLenses.map { it.id }
    return CameraCapabilities(
        allLenses = legacy.allLenses,
        flashModes = ids.associateWith { legacy.supportedFlashModes(it) },
        torchModes = ids.associateWith { legacy.supportedTorchModes(it) },
        supportsPauseResume = true,
        supportedVideoQualities = legacy.supportedVideoQualities,
        supportsVideoBitrate = legacy.supportsVideoBitrate,
        supportedAspectRatios = legacy.supportedAspectRatios,
        supportedImageFormats = legacy.supportedImageFormats,
        supportedAnalysisFormats = legacy.supportedAnalysisFormats,
        supportsSquareCapture = legacy.supportsSquareCapture,
    )
}

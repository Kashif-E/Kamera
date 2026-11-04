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
 * The devices are what [enumerate] lists (D2': the bridge's [DeviceEnumerator]; the one default
 * device for an engine a 1.x constructor built). A webcam has no flash, no torch and a fixed zoom
 * of 1, so flash and torch OFF and zoom 1 succeed while Open and anything else is Unsupported;
 * outside Open they are NotReady. The target orientation is configuration, accepted in every state
 * but closed and applied to nothing (§2.4 desktop). Capture and recording are refused with
 * `InvalidState` until D3', logging the first refusal of each kind at warning (§12.1). Until C1'
 * it carries the 1.x facade over itself, [legacyController].
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
    /** Where and how stills and recordings are written. */
    internal val media: DesktopMedia = DesktopMedia(),
    facade: (DesktopCameraEngine) -> CameraController = { CameraController(it, config) },
) : CameraEngine,
    LegacyControllerAccess {
    private val runtime = environment.runtime
    private val tracer: Tracer = runtime.tracer ?: DesktopLogTracer

    /** The producer the facade's captures and recordings read their frames from. */
    internal val grabber: CameraGrabber =
        CameraGrabber(MutableSharedFlow(), { }, null, nanoClock).apply { setHorizontalFlip(horizontalFlip) }

    override val legacyController: CameraController by lazy { facade(this) }

    /** The desired hardware the current session started with; null without one. */
    internal val startedWith: DesiredHardware? get() = synchronized(lock) { session?.desired }

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

    // ById must name an enumerated device (§4.1 row 2). ByLens takes the first device facing that
    // way, external cameras included, else the first device: a webcam faces no particular way, and
    // 1.x opened the one camera whatever lens was configured (§2.4 desktop).
    private fun select(capabilities: CameraCapabilities, selection: CameraSelection): LensInfo? = when (selection) {
        is CameraSelection.ById -> capabilities.allLenses.firstOrNull { it.id == selection.id }
        is CameraSelection.ByLens -> capabilities.allLenses.firstOrNull { it.lens == selection.lens }
            ?: capabilities.allLenses.firstOrNull()
    }

    private fun launchRunLocked(session: Session, switching: Boolean = false): Run {
        session.run?.let { replaced ->
            replaced.job?.cancel()
            replaced.firstFrame.complete(false)
        }
        val run = Run(session, switching)
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
        session.run?.firstFrame?.complete(false)
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
        // The first frame of this run: the platform confirmed the device it opened (§2.3 rule 1),
        // the session's first, a reopen's, or a camera switch's.
        if (run.firstFrame.complete(true)) {
            _hardware.value = HardwareState(FlashMode.OFF, TorchMode.OFF, session.device, null)
        }
        if (_state.value == EngineState.Open) return
        _state.value = EngineState.Open
        session.outcome.complete(CameraKResult.Success(Unit))
        startWatchdogLocked(session)
        if (!session.opened) {
            session.opened = true
            tracer.marker(TraceNames.FIRST_FRAME)
        }
    }

    private fun onRunFailure(run: Run, failure: Throwable) {
        // Before Open, or before a switched camera's first frame, this is a failed open; the macOS
        // authorization read may need JNA, so outside the lock.
        val opening = synchronized(lock) {
            isCurrentRun(run) && (!run.session.opened || run.switching && !run.firstFrame.isCompleted)
        }
        val openError = when {
            // The device was unplugged after enumeration (§2.4 desktop, §4.1 row 2).
            failure is DeviceGoneException -> CameraKError.NoCamera(null, failure)
            opening || JavaCvErrorMapper.isNativeLibraryMissing(failure) ->
                JavaCvErrorMapper.openFailure(failure, os) { authorization.cameraStatus() }
            else -> null
        }
        synchronized(lock) {
            val session = run.session
            if (!isCurrentRun(run)) return
            run.error = openError
            // A switch's device that does not open: the reopen below goes back to the device it had.
            if (run.switching && !run.firstFrame.isCompleted) run.revertTo?.let { session.device = it }
            run.firstFrame.complete(false)
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
                // A switch waiting for its device's first frame has its own bound (selectCamera).
                if (session.frames.get() == seen && !session.switchPending) {
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
        session.run?.firstFrame?.complete(false)
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

    /**
     * The devices, enumerated on the calling thread if the engine's own enumeration has not run
     * yet. The facade reads it: a 1.x engine lists the one default device without any I/O, and a
     * bridge engine's facade is reachable only after a start, which enumerated.
     */
    internal fun enumeratedCapabilities(): CameraCapabilities = capabilitiesNow()

    override suspend fun setFlashMode(mode: FlashMode): CameraKResult<FlashMode> = flashCommand(mode)

    /** [setFlashMode] without suspending, for the facade. A desktop camera's flash is OFF. */
    internal fun flashCommand(mode: FlashMode): CameraKResult<FlashMode> = hardwareCommand {
        when (mode) {
            FlashMode.OFF -> CameraKResult.Success(FlashMode.OFF)
            FlashMode.ON -> unsupported(CameraFeature.FLASH_ON)
            FlashMode.AUTO -> unsupported(CameraFeature.FLASH_AUTO)
        }
    }

    override suspend fun setTorchMode(mode: TorchMode): CameraKResult<TorchMode> = torchCommand(mode)

    /** [setTorchMode] without suspending, for the facade. A desktop camera has no torch. */
    internal fun torchCommand(mode: TorchMode): CameraKResult<TorchMode> = hardwareCommand {
        if (mode == TorchMode.OFF) CameraKResult.Success(TorchMode.OFF) else unsupported(CameraFeature.TORCH)
    }

    override suspend fun setZoom(ratio: Float): CameraKResult<ZoomState> = zoomCommand(ratio)

    /** [setZoom] without suspending, for the facade. A desktop camera's zoom is fixed at 1. */
    internal fun zoomCommand(ratio: Float): CameraKResult<ZoomState> = hardwareCommand {
        if (ratio == 1f) CameraKResult.Success(_zoom.value) else unsupported(CameraFeature.ZOOM)
    }

    // Hardware commands act on an open camera: Closed after close, NotReady in any other state but
    // Open (D1' carry-over 3, as the fake).
    private inline fun <T> hardwareCommand(command: () -> CameraKResult<T>): CameraKResult<T> {
        val state = synchronized(lock) {
            if (closed) return CameraKResult.Failure(CameraKError.Closed)
            _state.value
        }
        return if (state == EngineState.Open) command() else CameraKResult.Failure(CameraKError.NotReady)
    }

    private fun unsupported(feature: CameraFeature) = CameraKResult.Failure(CameraKError.Unsupported(feature))

    /**
     * Moves a live session to the device [selection] names. While Open it returns once that
     * device's first frame arrives; while opening the new device replaces the one being opened, and
     * while interrupted the recovery reopens the new one. Without a live session there is nothing
     * to move, and the next start opens what its own selection names. A device that fails to open
     * returns its open error, and the engine reopens the device it had. A device that opens but
     * delivers no frame within [NO_FRAME_TIMEOUT] is given up the same way, staying Open, and the
     * switch returns `Timeout(LENS)`, or `Timeout(DEVICE_TYPE)` when only the device type changed.
     */
    override suspend fun selectCamera(selection: CameraSelection): CameraKResult<LensInfo> {
        if (isClosed()) return CameraKResult.Failure(CameraKError.Closed)
        val capabilities = capabilitiesNow()
        val (device, run) = synchronized(lock) {
            if (closed) return CameraKResult.Failure(CameraKError.Closed)
            val device = select(capabilities, selection)
                ?: return CameraKResult.Failure(CameraKError.NoCamera((selection as? CameraSelection.ByLens)?.lens))
            val live = session?.takeUnless { it.ended } ?: return CameraKResult.Success(device)
            if (live.device?.id == device.id) return CameraKResult.Success(device)
            val waitForFrame = _state.value == EngineState.Open
            val previous = live.device
            live.device = device
            when {
                waitForFrame -> device to launchRunLocked(live, switching = true).also { it.revertTo = previous }
                _state.value is EngineState.Interrupted -> return CameraKResult.Success(device)
                else -> {
                    launchRunLocked(live)
                    return CameraKResult.Success(device)
                }
            }
        }
        // The engine's own first-frame bound: a device that opens and stays silent is given up.
        val opened = withTimeoutOrNull(NO_FRAME_TIMEOUT) { run.firstFrame.await() }
            ?: synchronized(lock) {
                if (run.firstFrame.isCompleted) null else return CameraKResult.Failure(revertLocked(run, device))
            }
            ?: run.firstFrame.await()
        if (opened) return CameraKResult.Success(device)
        return CameraKResult.Failure(synchronized(lock) { switchFailure(run) })
    }

    // A switch's device opened but delivered no frame in time: the device the session had opens
    // again, and the engine stays Open on it (D2' carry-over 3).
    private fun revertLocked(run: Run, target: LensInfo): CameraKError {
        val previous = run.revertTo
        if (!isCurrentRun(run) || previous == null) return switchFailure(run)
        CameraKLogger.w(TAG, "The selected camera delivered no frame in time; reopening the previous camera")
        val error = CameraKError.Timeout(switchOperation(previous, target))
        run.error = error
        run.firstFrame.complete(false)
        run.session.device = previous
        launchRunLocked(run.session)
        return error
    }

    private fun switchFailure(run: Run): CameraKError = run.error ?: when (val state = _state.value) {
        is EngineState.Failed -> state.error
        else -> if (closed) CameraKError.Closed else CameraKError.NotReady
    }

    /** Configuration, not a hardware command: accepted in every state but closed, and a no-op on desktop (G2). */
    override suspend fun setTargetOrientation(orientation: DeviceOrientation?): CameraKResult<Unit> =
        if (isClosed()) CameraKResult.Failure(CameraKError.Closed) else CameraKResult.Success(Unit)

    override suspend fun capture(): CameraKResult<CapturedImage> = refuse(CameraOperation.CAPTURE)

    override suspend fun prepareRecording(config: VideoConfiguration): CameraKResult<Unit> =
        refuse(CameraOperation.RECORD_PREPARE)

    override suspend fun startRecording(config: VideoConfiguration): CameraKResult<String> =
        refuse(CameraOperation.RECORD_START)

    override suspend fun stopRecording(): CameraKResult<RecordedVideo> = refuse(CameraOperation.RECORD_STOP)

    override suspend fun pauseRecording(): CameraKResult<Unit> = refuse(CameraOperation.RECORD_PAUSE)

    override suspend fun resumeRecording(): CameraKResult<Unit> = refuse(CameraOperation.RECORD_RESUME)

    private fun isClosed(): Boolean = synchronized(lock) { closed }

    // Until D3' gives the engine capture and recording, the facade carries them (§12.1).
    private fun refuse(operation: CameraOperation): CameraKResult<Nothing> {
        if (isClosed()) return CameraKResult.Failure(CameraKError.Closed)
        if (operation !in refused.getAndUpdate { it + operation }) {
            CameraKLogger.w(TAG, "The desktop engine refuses the $operation command; issue it on Ready.controller")
        }
        return CameraKResult.Failure(CameraKError.InvalidState(operation))
    }

    /**
     * DEVICE_TYPE when a switch from [from] to [to] keeps the lens and changes only the device type,
     * LENS otherwise (Decision 7).
     */
    private fun switchOperation(from: LensInfo, to: LensInfo): CameraOperation = when {
        from.lens == to.lens && from.deviceType != to.deviceType -> CameraOperation.DEVICE_TYPE
        else -> CameraOperation.LENS
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

        /** A camera switch is waiting for its device's first frame. */
        val switchPending: Boolean get() = run?.let { it.switching && !it.firstFrame.isCompleted } == true
    }

    /** One grab loop of a session: the first, a reopen while interrupted, or a camera switch's. */
    private class Run(val session: Session, val switching: Boolean) {
        var job: Job? = null

        /** True at the run's first frame; false when it fails, ends or is replaced before one. */
        val firstFrame = CompletableDeferred<Boolean>()

        /** The open error of a run that failed before its first frame. */
        var error: CameraKError? = null

        /** The device a switch moved away from, reopened when the switch's device does not open. */
        var revertTo: LensInfo? = null
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

/**
 * The one default entry: the 1.x enumerator's camera, which opens the system's default camera as
 * 1.x did. Offered alone when the devices cannot be listed, and by a grabber-factory engine with no
 * local camera (§2.4 desktop).
 */
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
 * The capabilities of the desktop cameras [lenses] (the 1.x enumerator's one camera by default):
 * flash and torch OFF only (from `hasFlash`), and pause and resume through the common RecordingClock.
 * Everything else keeps the 1.x constructor's values until D3' and D4' fill it.
 */
internal fun desktopCapabilities(lenses: List<LensInfo> = listOf(desktopLensInfo())): CameraCapabilities {
    val legacy = CameraCapabilities(lenses)
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

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
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.result.map
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.ZoomState
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import com.kashif.cameraK.video.VideoQuality
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.image.BufferedImage
import java.io.File
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
 * but closed and applied to nothing (§2.4 desktop).
 *
 * Captures and recordings (D3') are app-owned files ([media]): stills under the configuration's
 * `outputDirectory`, else `Documents/CameraK` under the user's home, whatever the `Directory`
 * (gallery publishing is the media saver's, §6.1); recordings under their own `outputDirectory`,
 * else the configuration's, else the user's movies folder. HEIC is not a desktop format, so a start
 * configured with it fails with `Unsupported(IMAGE_FORMAT)`. A recording ([DesktopRecording]) is
 * video only (`enableAudio` is `Unsupported(AUDIO)`), enforces its limits itself, yields one
 * `Finalized` in [recordingEvents], and is finalized before the camera closes on [stop] or [close]
 * (§2.3 rule 7). A camera switch during one is `Busy`. Until C1' it carries the 1.x facade over
 * itself, [legacyController].
 */
internal class DesktopCameraEngine(
    private val config: CameraConfiguration,
    environment: CameraKEnvironment,
    private val source: GrabberSource,
    private val authorization: CameraAuthorization = MacCameraAuthorization(),
    private val os: OperatingSystem = currentOperatingSystem(),
    private val nanoClock: () -> Long = System::nanoTime,
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
    private val io = environment.dispatchers.io

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

    // Emitted under lock, so every recording's events are in order and recordings follow start order.
    private val _recordingEvents = MutableSharedFlow<EngineRecordingEvent>(extraBufferCapacity = EVENT_BUFFER)
    override val recordingEvents: Flow<EngineRecordingEvent> = _recordingEvents.asSharedFlow()
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

    // The recording from its registration until its Finalized; a start is in flight before that.
    private var recording: DesktopRecording? = null
    private var recordingStarting = false
    private val pendingCaptures = mutableSetOf<CompletableDeferred<CameraKResult<CapturedImage>>>()

    private val enumeration = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { enumerate() }
    private val directoryIgnored = atomic(false)

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
        // Checked before any device opens: a configuration the platform cannot honour fails the start (§6.1).
        if (config.imageFormat == ImageFormat.HEIC) {
            CameraKLogger.w(TAG, "HEIC stills are not supported on desktop; the start fails as Unsupported")
            synchronized(lock) {
                if (isCurrent(session)) failLocked(session, CameraKError.Unsupported(CameraFeature.IMAGE_FORMAT))
            }
            return
        }
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

    /**
     * Finalizes a live recording first (§2.3 rule 7: it ends `Finalized(Success, SHUTDOWN)`), then
     * ends the session and waits (cancellably) until every grab loop released the camera and its lock.
     */
    override suspend fun stop() {
        synchronized(lock) { if (closed) null else recording }?.let { live ->
            live.requestStop(RecordingEndReason.SHUTDOWN)
            live.finalized.join()
        }
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

    // [keepRun] leaves the grab loop running for a recording still to finalize; it ends with the scope.
    private fun endLocked(session: Session, keepRun: Boolean = false) {
        session.ended = true
        session.watchdog?.cancel()
        session.recovery?.cancel()
        if (!keepRun) session.run?.job?.cancel()
        session.run?.firstFrame?.complete(false)
        session.outcome.complete(CameraKResult.Failure(CameraKError.Closed))
    }

    /**
     * Terminal and non-blocking. Pending captures complete with `Failure(Closed)` at once. A live
     * recording is finalized first (§2.3 rule 7), on its own thread, and the camera is released
     * after it: the grab loop runs until then.
     */
    override fun close() {
        val live = synchronized(lock) {
            if (closed) return
            closed = true
            pendingCaptures.forEach { it.complete(CameraKResult.Failure(CameraKError.Closed)) }
            pendingCaptures.clear()
            val live = recording
            session?.let { endLocked(it, keepRun = live != null) }
            session = null
            previewFrames.value = null
            _state.value = EngineState.Closed
            live
        }
        // Each grab loop still releases the camera in its own finally; closeAndJoin waits for that.
        if (live == null) {
            scope.cancel()
            return
        }
        live.requestStop(RecordingEndReason.SHUTDOWN)
        live.finalized.invokeOnCompletion { scope.cancel() }
    }

    override suspend fun closeAndJoin() {
        val live = synchronized(lock) { recording }
        close()
        live?.finalized?.join()
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
            // A recording keeps its camera (Decision 7).
            if (recording != null || recordingStarting) {
                val current = live.device ?: device
                return CameraKResult.Failure(CameraKError.Busy(switchOperation(current, device)))
            }
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
        val opened = firstFrameWithinTimeout(run)
            ?: synchronized(lock) {
                if (run.firstFrame.isCompleted) null else return CameraKResult.Failure(revertLocked(run, device))
            }
            ?: run.firstFrame.await()
        if (opened) return CameraKResult.Success(device)
        return CameraKResult.Failure(synchronized(lock) { switchFailure(run) })
    }

    // Timed on the engine's dispatcher, as the no-frame watchdog is, not on the caller's: null when
    // NO_FRAME_TIMEOUT passed first, or the engine's scope ended.
    private suspend fun firstFrameWithinTimeout(run: Run): Boolean? {
        val bound = scope.launch { delay(NO_FRAME_TIMEOUT) }
        return try {
            select {
                run.firstFrame.onAwait { it }
                bound.onJoin { null }
            }
        } finally {
            bound.cancel()
        }
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

    /** A still of the camera's next frame, written to an app-owned file; NotReady unless Open. */
    override suspend fun capture(): CameraKResult<CapturedImage> = still(requireOpen = true)

    /**
     * A still of the producer's next frame. [requireOpen] false is the 1.x facade's capture, which
     * waits for the next frame in any state, as 1.x did. Completes with `Failure(Closed)` at once
     * when the engine closes while it waits.
     */
    internal suspend fun still(requireOpen: Boolean): CameraKResult<CapturedImage> {
        val pending = CompletableDeferred<CameraKResult<CapturedImage>>()
        synchronized(lock) {
            if (closed) return CameraKResult.Failure(CameraKError.Closed)
            if (requireOpen && _state.value != EngineState.Open) return CameraKResult.Failure(CameraKError.NotReady)
            pendingCaptures += pending
        }
        val writer = scope.launch(io) {
            val written = writeStill()
            // Closed while it was written: the caller has its answer, and the file is not kept.
            if (!pending.complete(written) && written is CameraKResult.Success) File(written.value.path).delete()
        }
        return try {
            pending.await()
        } catch (e: CancellationException) {
            writer.cancel()
            throw e
        } finally {
            synchronized(lock) { pendingCaptures -= pending }
        }
    }

    private suspend fun writeStill(): CameraKResult<CapturedImage> {
        val running = grabber.isRunning
        val image = grabber.awaitSnapshot() ?: return CameraKResult.Failure(
            if (running) CameraKError.Timeout(CameraOperation.CAPTURE) else CameraKError.NotReady,
        )
        val format = config.imageFormat
        if (format ==
            ImageFormat.HEIC
        ) {
            return CameraKResult.Failure(CameraKError.Unsupported(CameraFeature.IMAGE_FORMAT))
        }
        val folder = stillFolder()
        var file: File? = null
        return try {
            val written = createUniqueFile(folder, "CameraK", if (format == ImageFormat.PNG) "png" else "jpg")
            file = written
            media.writeCapture(image, format, written)
            CameraKResult.Success(CapturedImage(written.absolutePath, format, image.width, image.height))
        } catch (e: CancellationException) {
            file?.delete()
            throw e
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Writing a capture failed; nothing is kept", e)
            file?.delete()
            CameraKResult.Failure(JavaCvErrorMapper.captureFailure(e, isDiskFull(folder)))
        }
    }

    // The configuration's folder, else the app-owned default; PICTURES and DCIM are the media saver's.
    private fun stillFolder(): File {
        config.outputDirectory?.let { return File(it) }
        if (config.directory != Directory.DOCUMENTS && directoryIgnored.compareAndSet(false, true)) {
            CameraKLogger.w(
                TAG,
                "Core writes app-owned files only: Directory.${config.directory} is not read; " +
                    "publish to the gallery with the media saver",
            )
        }
        return File(File(media.userHome, "Documents"), "CameraK")
    }

    private fun videoFolder(video: VideoConfiguration): File =
        (video.outputDirectory ?: config.outputDirectory)?.let(::File)
            ?: File(File(media.userHome, moviesFolderName()), "CameraK")

    /** Validates what [startRecording] would refuse; desktop has no output to add ahead of time. */
    override suspend fun prepareRecording(config: VideoConfiguration): CameraKResult<Unit> {
        if (isClosed()) return CameraKResult.Failure(CameraKError.Closed)
        if (config.enableAudio) return unsupportedAudio()
        return onIo {
            try {
                createOwnerOnlyDirectory(videoFolder(config))
                CameraKResult.Success(Unit)
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "Preparing a recording failed: its folder cannot be created", e)
                CameraKResult.Failure(notWritable(e))
            }
        }
    }

    /**
     * Runs [block] on the engine's I/O dispatcher in its own scope, so blocking file and recorder
     * work never runs on the caller's thread and is not abandoned halfway by a cancelled caller.
     * `Failure(Closed)` when the engine closed first.
     */
    private suspend fun <T> onIo(block: suspend () -> CameraKResult<T>): CameraKResult<T> {
        val work = scope.async(io) { block() }
        return try {
            work.await()
        } catch (e: CancellationException) {
            if (work.isCancelled &&
                currentCoroutineContext().isActive
            ) {
                CameraKResult.Failure(CameraKError.Closed)
            } else {
                throw e
            }
        }
    }

    override suspend fun startRecording(config: VideoConfiguration): CameraKResult<String> =
        beginRecording(config, requireOpen = true).map { it.path }

    /**
     * Starts a recording and returns it once the recorder started and `Started` was emitted.
     * [requireOpen] false is the 1.x facade's start, which records whatever frames come, as 1.x did,
     * and keeps the recording to claim its outcome.
     */
    internal suspend fun beginRecording(
        video: VideoConfiguration,
        requireOpen: Boolean,
    ): CameraKResult<DesktopRecording> {
        synchronized(lock) {
            if (closed) return CameraKResult.Failure(CameraKError.Closed)
            if (requireOpen && _state.value != EngineState.Open) return CameraKResult.Failure(CameraKError.NotReady)
            if (recording != null || recordingStarting) {
                return CameraKResult.Failure(CameraKError.Busy(CameraOperation.RECORD_START))
            }
            if (video.enableAudio) return unsupportedAudio()
            recordingStarting = true
        }
        // In the engine's scope: a caller that gives up leaves a recording the engine can still stop.
        return onIo {
            try {
                startNow(video)
            } finally {
                synchronized(lock) { recordingStarting = false }
            }
        }
    }

    private suspend fun startNow(video: VideoConfiguration): CameraKResult<DesktopRecording> {
        val live = when (val created = newRecording(video)) {
            is CameraKResult.Failure -> return created
            is CameraKResult.Success -> created.value
        }
        // Readied before it is listed, so a stop or close always reaches it, and releases it if it never ran.
        live.prepare(scope, runtime.newWorker(RECORDER_THREAD), grabber.frames(), grabber.frameSize)
        synchronized(lock) {
            if (closed) {
                live.requestStop(RecordingEndReason.SHUTDOWN)
                return CameraKResult.Failure(CameraKError.Closed)
            }
            recording = live
        }
        val outcome = live.start()
        if (outcome is CameraKResult.Failure) synchronized(lock) { if (recording === live) recording = null }
        return outcome.map { live }
    }

    // The output file and an unstarted recorder for it.
    private fun newRecording(video: VideoConfiguration): CameraKResult<DesktopRecording> {
        val folder = videoFolder(video)
        val file = try {
            createUniqueFile(folder, video.filePrefix, "mp4")
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Creating a recording's file failed", e)
            return CameraKResult.Failure(JavaCvErrorMapper.captureFailure(e, isDiskFull(folder)))
        }
        val recorder = try {
            media.recorderFactory(file.absolutePath, video)
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Creating a recorder failed", e)
            file.delete()
            val error = JavaCvErrorMapper.recordingFailure(e, playable = false, file.absolutePath, isDiskFull(folder))
            return CameraKResult.Failure(error ?: CameraKError.Internal(e))
        }
        val converter = media.recordingConverter()
        return CameraKResult.Success(
            DesktopRecording(file.absolutePath, video, recorder, converter, nanoClock, ::isDiskFull, recordingListener),
        )
    }

    private val recordingListener = object : RecordingListener {
        override fun onStarted(recording: DesktopRecording): Boolean = synchronized(lock) {
            if (closed || this@DesktopCameraEngine.recording !== recording) return false
            emitLocked(EngineRecordingEvent.Started(recording.path, hasAudio = false))
            true
        }

        override fun onFinalized(
            recording: DesktopRecording,
            result: CameraKResult<RecordedVideo>,
            reason: RecordingEndReason,
        ) = synchronized(lock) {
            if (this@DesktopCameraEngine.recording === recording) this@DesktopCameraEngine.recording = null
            emitLocked(EngineRecordingEvent.Finalized(result, reason))
        }
    }

    /** Finalizes the live recording and returns its outcome; `InvalidState` when none was begun. */
    override suspend fun stopRecording(): CameraKResult<RecordedVideo> {
        val live = synchronized(lock) {
            if (closed) return CameraKResult.Failure(CameraKError.Closed)
            recording ?: return CameraKResult.Failure(CameraKError.InvalidState(CameraOperation.RECORD_STOP))
        }
        live.requestStop(RecordingEndReason.USER)
        return live.finalized.await()
    }

    /** Pauses the recording's clock: frames from now are not written, and the pause is left out of the file. */
    override suspend fun pauseRecording(): CameraKResult<Unit> = synchronized(lock) {
        if (closed) return CameraKResult.Failure(CameraKError.Closed)
        val live = recording?.takeIf { it.isStarted }
            ?: return CameraKResult.Failure(CameraKError.InvalidState(CameraOperation.RECORD_PAUSE))
        if (live.pause()) emitLocked(EngineRecordingEvent.Paused)
        CameraKResult.Success(Unit)
    }

    override suspend fun resumeRecording(): CameraKResult<Unit> = synchronized(lock) {
        if (closed) return CameraKResult.Failure(CameraKError.Closed)
        val live = recording?.takeIf { it.isStarted }
            ?: return CameraKResult.Failure(CameraKError.InvalidState(CameraOperation.RECORD_RESUME))
        if (live.resume()) emitLocked(EngineRecordingEvent.Resumed)
        CameraKResult.Success(Unit)
    }

    private fun emitLocked(event: EngineRecordingEvent) {
        if (!_recordingEvents.tryEmit(event)) {
            CameraKLogger.e(TAG, "A recording event was dropped: its collector is too far behind")
        }
    }

    // Decision 16: desktop records video only.
    private fun unsupportedAudio(): CameraKResult.Failure {
        CameraKLogger.w(TAG, "Desktop records no audio; a recording with enableAudio fails as Unsupported")
        return CameraKResult.Failure(CameraKError.Unsupported(CameraFeature.AUDIO))
    }

    private fun isClosed(): Boolean = synchronized(lock) { closed }

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
        const val RECORDER_THREAD = "CameraK-recorder"

        // Far more than one recording's events; a collector this far behind is a bug, and is logged.
        const val EVENT_BUFFER = 64
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
 * flash and torch OFF only (from `hasFlash`), pause and resume through the common RecordingClock,
 * JPEG and PNG stills without HEIC, and all four video qualities with the quality's bitrate as the
 * encoder's target (D3', §6.3). The recording has the camera's frame size, which `RecordedVideo`
 * reports. Aspect ratios and analysis formats keep the 1.x constructor's values until D4'.
 */
internal fun desktopCapabilities(lenses: List<LensInfo> = listOf(desktopLensInfo())): CameraCapabilities {
    val legacy = CameraCapabilities(lenses)
    val ids = legacy.allLenses.map { it.id }
    return CameraCapabilities(
        allLenses = legacy.allLenses,
        flashModes = ids.associateWith { legacy.supportedFlashModes(it) },
        torchModes = ids.associateWith { legacy.supportedTorchModes(it) },
        supportsPauseResume = true,
        supportedVideoQualities = VideoQuality.entries.toSet(),
        supportsVideoBitrate = true,
        supportedAspectRatios = legacy.supportedAspectRatios,
        supportedImageFormats = setOf(ImageFormat.JPEG, ImageFormat.PNG),
        supportedAnalysisFormats = legacy.supportedAnalysisFormats,
        supportsSquareCapture = legacy.supportsSquareCapture,
    )
}

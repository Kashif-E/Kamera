@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.recording

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.coroutines.suspendRunCatching
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.EngineRecordingEvent
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.CameraKPermissionChecker
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.result.getOrNull
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.state.CommandRunner
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * How long a recording start waits for the platform to confirm it before it gives up with
 * `Timeout(RECORD_START)` and stops whatever the engine began.
 */
internal val RECORDING_START_TIMEOUT: Duration = 5.seconds

/**
 * How long `close()` waits for the recording to finalize before it goes on to close the camera, so
 * a platform that never finalizes cannot keep the holder open. A host stop is bounded by it too.
 */
internal val SHUTDOWN_STOP_TIMEOUT: Duration = 5.seconds

/** How often `uiState.recordingDurationMs` moves while recording: the 1.x ticker's rate. */
internal val RECORDING_TICK: Duration = 250.milliseconds

/**
 * The holder's one recording: the owner of [state] and the only path to the engine's recording
 * calls, and, for a host stop, to `engine.stop()`. Transitions that wait on the platform hold one
 * [Mutex], so a stop during a start waits for the start to resolve and then stops. Calls that only
 * read the state (a second start, a camera switch) answer at once.
 *
 * The engine's recording events are collected for the holder's lifetime, so a recording the platform
 * ends on its own (a limit, an error, an interruption) always reaches [state] as Finished and the
 * app as [CameraKEvent.RecordingEnded]. A stop the caller asked for returns its outcome instead.
 *
 * @param commands Runs each platform call as a command job in the holder's scope, on its default
 *   dispatcher, so a caller that is cancelled never cancels a finalization.
 * @param emit Delivers an event to the holder's events.
 */
internal class RecordingController(
    private val engine: CameraEngine,
    private val commands: CommandRunner,
    private val scope: CoroutineScope,
    private val timeSource: TimeSource.WithComparableMarks,
    private val permissionChecker: CameraKPermissionChecker?,
    private val emit: (CameraKEvent) -> Unit,
) {
    private val lock = SynchronizedObject()
    private val transitions = Mutex()
    private val origin: ComparableTimeMark = timeSource.markNow()

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)

    /** The recorded time from [RecordingClock], published every [RECORDING_TICK] and at each pause and resume. */
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    // Guarded by lock.
    private var lastId = 0L
    private var startingId = 0L
    private var startJob: Job? = null
    private var stopJob: Deferred<CameraKResult<RecordedVideo>>? = null
    private var hasAudio = false
    private var clock: RecordingClock? = null
    private var ticker: Job? = null

    // What the engine reported for the start in flight: its Started, or an end before it.
    private val startSignal = MutableStateFlow<EngineRecordingEvent?>(null)

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.recordingEvents.collect { event ->
                suspendRunCatching { onEngineEvent(event) }.onFailure { e ->
                    CameraKLogger.e(TAG, "Handling a recording event failed; the event was dropped", e)
                }
            }
        }
    }

    /** Starting, Recording, Paused or Stopping: a recording is live and the camera may not change. */
    fun isLive(): Boolean = when (_state.value) {
        RecordingState.Idle, is RecordingState.Finished -> false
        else -> true
    }

    suspend fun prepare(config: VideoConfiguration): CameraKResult<Unit> {
        if (isLive()) return CameraKResult.Failure(CameraKError.Busy(CameraOperation.RECORD_PREPARE))
        return commands.run(CameraOperation.RECORD_PREPARE) { engine.prepareRecording(config) }
    }

    suspend fun start(config: VideoConfiguration): CameraKResult<RecordingState.Recording> {
        val job = synchronized(lock) {
            val current = _state.value
            if (current != RecordingState.Idle && current !is RecordingState.Finished) {
                return CameraKResult.Failure(CameraKError.Busy(CameraOperation.RECORD_START))
            }
            val id = ++lastId
            startingId = id
            startSignal.value = null
            _state.value = RecordingState.Starting(config)
            commands.start(CameraOperation.RECORD_START) { startUnderLock(config, id) }.also { startJob = it }
        }
        return commands.await(job)
    }

    suspend fun stop(): CameraKResult<RecordedVideo> {
        val job = stopJob(RecordingEndReason.USER)
            ?: return CameraKResult.Failure(CameraKError.InvalidState(CameraOperation.RECORD_STOP))
        return commands.await(job)
    }

    suspend fun pause(): CameraKResult<Unit> {
        if (_state.value !is RecordingState.Recording) {
            return CameraKResult.Failure(CameraKError.InvalidState(CameraOperation.RECORD_PAUSE))
        }
        if (!engine.capabilities.value.supportsPauseResume) {
            return CameraKResult.Failure(CameraKError.Unsupported(CameraFeature.PAUSE_RESUME))
        }
        return commands.run(CameraOperation.RECORD_PAUSE) {
            transitions.withLock {
                val recording = _state.value as? RecordingState.Recording
                    ?: return@withLock CameraKResult.Failure(CameraKError.InvalidState(CameraOperation.RECORD_PAUSE))
                engine.pauseRecording().also { if (it is CameraKResult.Success) paused(recording) }
            }
        }
    }

    suspend fun resume(): CameraKResult<Unit> {
        if (_state.value !is RecordingState.Paused) {
            return CameraKResult.Failure(CameraKError.InvalidState(CameraOperation.RECORD_RESUME))
        }
        return commands.run(CameraOperation.RECORD_RESUME) {
            transitions.withLock {
                val paused = _state.value as? RecordingState.Paused
                    ?: return@withLock CameraKResult.Failure(CameraKError.InvalidState(CameraOperation.RECORD_RESUME))
                engine.resumeRecording().also { if (it is CameraKResult.Success) resumed(paused) }
            }
        }
    }

    /**
     * The host stopped the camera: a live recording ends as INTERRUPTED, then the camera stops. Called
     * by the session reconciler in place of `engine.stop()`, in `NonCancellable`.
     */
    suspend fun stopForHostStop() {
        stopJob(RecordingEndReason.INTERRUPTED)?.let { awaitBounded(it, "the host stopped the camera") }
        engine.stop()
    }

    /**
     * The first step of close, while the camera is still open: a start in flight is cancelled, so its
     * caller gets `Failure(Closed)`, and a live recording ends as SHUTDOWN, bounded by
     * [SHUTDOWN_STOP_TIMEOUT].
     */
    suspend fun stopForShutdown() {
        val pendingStart = synchronized(lock) { startJob?.takeIf { it.isActive } }
        if (pendingStart != null) {
            pendingStart.cancel()
            withTimeoutOrNull(SHUTDOWN_STOP_TIMEOUT) { pendingStart.join() }
        }
        stopJob(RecordingEndReason.SHUTDOWN)?.let { awaitBounded(it, "the holder closed") }
    }

    private suspend fun awaitBounded(job: Deferred<CameraKResult<RecordedVideo>>, cause: String) {
        val ended = withTimeoutOrNull(SHUTDOWN_STOP_TIMEOUT) { commands.await(job) }
        if (ended == null) {
            CameraKLogger.e(
                TAG,
                "Stopping the recording when $cause timed out; the camera stops without its finalization",
                RecordingStopTimeoutException(SHUTDOWN_STOP_TIMEOUT),
            )
        }
    }

    // The stop in flight, which every later stop shares, or a new one; null when nothing is live.
    private fun stopJob(reason: RecordingEndReason): Deferred<CameraKResult<RecordedVideo>>? = synchronized(lock) {
        stopJob?.takeIf { it.isActive }?.let { return it }
        if (!isLive()) return null
        commands.start(CameraOperation.RECORD_STOP) { stopUnderLock(reason) }.also { stopJob = it }
    }

    private suspend fun startUnderLock(config: VideoConfiguration, id: Long): CameraKResult<RecordingState.Recording> =
        transitions.withLock {
            var outcome: CameraKResult<RecordingState.Recording>? = null
            var engineCalled = false
            try {
                microphoneRefusal(config)?.let {
                    outcome = it
                    return@withLock it.also { backToIdle() }
                }
                engineCalled = true
                val started = withTimeoutOrNull(RECORDING_START_TIMEOUT) { confirmedStart(config, id) }
                outcome = started ?: run {
                    withContext(NonCancellable) { stopUnconfirmed("timed out") }
                    CameraKResult.Failure(CameraKError.Timeout(CameraOperation.RECORD_START))
                }
                outcome
            } finally {
                // A throwable or close's cancellation: the start ends with no recording left running.
                if (outcome == null) {
                    withContext(NonCancellable) { if (engineCalled) stopUnconfirmed("was abandoned") else backToIdle() }
                }
                synchronized(lock) { startJob = null }
            }
        }

    private fun microphoneRefusal(config: VideoConfiguration): CameraKResult.Failure? {
        if (!config.enableAudio) return null
        val status = permissionChecker?.status(CameraKPermission.MICROPHONE) ?: PermissionStatus.Granted
        if (status == PermissionStatus.Granted) return null
        return CameraKResult.Failure(CameraKError.Permission(CameraKPermission.MICROPHONE, status))
    }

    // The engine's start, then its Started milestone, which the event collector turns into Recording.
    private suspend fun confirmedStart(config: VideoConfiguration, id: Long): CameraKResult<RecordingState.Recording> {
        val path = when (val started = engine.startRecording(config)) {
            is CameraKResult.Failure -> return started.also { backToIdle() }
            is CameraKResult.Success -> started.value
        }
        return when (val milestone = startSignal.filterNotNull().first()) {
            is EngineRecordingEvent.Started -> CameraKResult.Success(
                _state.value as? RecordingState.Recording ?: becomeRecording(id, milestone.path, milestone.hasAudio),
            )
            is EngineRecordingEvent.Finalized -> {
                // The platform confirmed the start and ended the recording before reporting it running.
                val hasAudio = milestone.result.getOrNull()?.hasAudio ?: config.enableAudio
                val recording = RecordingState.Recording(id, path, timeSource.markNow(), Duration.ZERO, hasAudio)
                finish(id, milestone.result, milestone.reason)
                CameraKResult.Success(recording)
            }
            else -> error("Only Started and Finalized signal a start")
        }
    }

    // A start that was not confirmed may still be running on the platform: stop it, and go back to Idle.
    private suspend fun stopUnconfirmed(why: String) {
        CameraKLogger.w(TAG, "A recording start $why; stopping what the platform began")
        withTimeoutOrNull(SHUTDOWN_STOP_TIMEOUT) { engine.stopRecording() }
        backToIdle()
    }

    private suspend fun stopUnderLock(reason: RecordingEndReason): CameraKResult<RecordedVideo> = transitions.withLock {
        val stopping = synchronized(lock) {
            val stopping = when (val current = _state.value) {
                is RecordingState.Recording -> RecordingState.Stopping(current.recordingId, current.filePath, reason)
                is RecordingState.Paused -> RecordingState.Stopping(current.recordingId, current.filePath, reason)
                else -> null
            }
            stopping?.also { _state.value = it }
        } ?: return@withLock CameraKResult.Failure(CameraKError.InvalidState(CameraOperation.RECORD_STOP))
        val result = try {
            suspendRunCatching { engine.stopRecording() }.getOrElse { e ->
                CameraKLogger.e(TAG, "Stopping the recording threw; it ends as Internal", e)
                CameraKResult.Failure(CameraKError.Internal(e))
            }
        } catch (e: CancellationException) {
            // Only the holder's own end cancels a stop; the recording still ends, without its file.
            finish(stopping.recordingId, CameraKResult.Failure(CameraKError.Closed), reason)
            throw e
        }
        finish(stopping.recordingId, result, reason)
        result
    }

    private fun onEngineEvent(event: EngineRecordingEvent) {
        when (event) {
            is EngineRecordingEvent.Started -> synchronized(lock) {
                if (_state.value !is RecordingState.Starting) return
                becomeRecording(startingId, event.path, event.hasAudio)
                startSignal.value = event
            }
            is EngineRecordingEvent.Finalized -> {
                val ended = synchronized(lock) {
                    when (val current = _state.value) {
                        is RecordingState.Starting -> {
                            startSignal.value = event
                            null
                        }
                        is RecordingState.Recording -> current.recordingId
                        is RecordingState.Paused -> current.recordingId
                        // Stopping: the stop's own return is the outcome.
                        else -> null
                    }
                }
                if (ended != null) finish(ended, event.result, event.reason)
            }
            // The pause and resume commands move the state when the engine returns.
            EngineRecordingEvent.Paused, EngineRecordingEvent.Resumed -> Unit
        }
    }

    private fun becomeRecording(id: Long, path: String, withAudio: Boolean): RecordingState.Recording =
        synchronized(lock) {
            val recording = RecordingState.Recording(id, path, timeSource.markNow(), Duration.ZERO, withAudio)
            hasAudio = withAudio
            clock = RecordingClock(nowNanos())
            _durationMs.value = 0L
            _state.value = recording
            ticker?.cancel()
            ticker = scope.launch {
                while (true) {
                    delay(RECORDING_TICK)
                    publishDuration()
                }
            }
            recording
        }

    private fun paused(recording: RecordingState.Recording) = synchronized(lock) {
        if (_state.value !== recording) return
        clock?.pause(nowNanos())
        publishDuration()
        _state.value = RecordingState.Paused(
            recording.recordingId,
            recording.filePath,
            recording.accumulated + recording.activeSince.elapsedNow(),
        )
    }

    private fun resumed(paused: RecordingState.Paused) = synchronized(lock) {
        if (_state.value !== paused) return
        clock?.resume(nowNanos())
        publishDuration()
        _state.value = RecordingState.Recording(
            paused.recordingId,
            paused.filePath,
            timeSource.markNow(),
            paused.accumulated,
            hasAudio,
        )
    }

    // Any end: Finished for display, and RecordingEnded unless the caller asked for it.
    private fun finish(id: Long, result: CameraKResult<RecordedVideo>, reason: RecordingEndReason) {
        synchronized(lock) {
            stopTicking()
            _state.value = RecordingState.Finished(id, result, reason)
        }
        if (reason != RecordingEndReason.USER) emit(CameraKEvent.RecordingEnded(id, result, reason))
    }

    private fun backToIdle() = synchronized(lock) {
        stopTicking()
        _state.value = RecordingState.Idle
    }

    private fun stopTicking() {
        ticker?.cancel()
        ticker = null
        clock = null
        _durationMs.value = 0L
    }

    private fun publishDuration() = synchronized(lock) {
        clock?.let { _durationMs.value = it.recordedMillis(nowNanos()) }
    }

    private fun nowNanos(): Long = origin.elapsedNow().inWholeNanoseconds

    private companion object {
        const val TAG = "CameraK"
    }
}

/** The recording did not finalize within [SHUTDOWN_STOP_TIMEOUT]; logged, never thrown. */
internal class RecordingStopTimeoutException(timeout: Duration) :
    IllegalStateException("The recording did not finalize within $timeout")

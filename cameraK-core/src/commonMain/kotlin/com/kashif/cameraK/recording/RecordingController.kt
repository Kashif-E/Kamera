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
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.state.CommandRunner
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoCaptureResult
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
 * How long a stop waits for the platform to finalize the recording: past it, the recording ends as
 * `Timeout(RECORD_STOP)`. `close()` and a host stop also wait at most this long for the recording
 * step as a whole, so a platform that never finalizes cannot keep the camera open.
 */
internal val SHUTDOWN_STOP_TIMEOUT: Duration = 5.seconds

/** How often `uiState.recordingDurationMs` moves while recording: the 1.x ticker's rate. */
internal val RECORDING_TICK: Duration = 250.milliseconds

/**
 * The holder's one recording: the owner of [state] and the only path to the engine's recording
 * calls, and, for a host stop, to `engine.stop()`. Transitions that wait on the platform hold one
 * [Mutex], so a stop during a start waits for the start to resolve and then stops. Calls that only
 * read the state (a second start, a camera switch) answer at once. Each state change is one critical
 * section of [lock] that checks the phase it expects for that recording before it writes.
 *
 * The engine's recording events are collected for the holder's lifetime, so a recording the platform
 * ends on its own (a limit, an error, an interruption) always reaches [state] as Finished and the
 * app as [CameraKEvent.RecordingEnded]. A stop the caller asked for returns its outcome instead.
 *
 * The engine yields one `Finalized` per recording it started, in start order, possibly after
 * `stopRecording` returned (`CameraEngine.recordingEvents`). A recording the holder ended before its
 * `Finalized` arrived owes one, and the next `Finalized` pays the oldest debt: it is drained, so a
 * late end never reaches the recording after it.
 *
 * Until C1' removes them, the deprecated 1.x events are emitted beside the 2.0 ones, so a 1.x
 * listener keeps hearing about recordings: [CameraKEvent.RecordingStarted] when a start becomes
 * Recording (not on resume), [CameraKEvent.RecordingStopped] for every end with a file,
 * [CameraKEvent.RecordingFailed] for every end with a failure, and
 * [CameraKEvent.RecordingMaxDurationReached] first when the duration limit ended it.
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
    private var startAbandoned = false
    private var startJob: Job? = null
    private var stopJob: Deferred<CameraKResult<RecordedVideo>>? = null
    private var hasAudio = false
    private var clock: RecordingClock? = null
    private var ticker: Job? = null

    // Guarded by lock: the Finalized events still owed by recordings already ended, and whether the
    // current recording's own has arrived.
    private var owedFinalizes = 0
    private var finalizeSeen = false

    // What the engine reported for the start in flight: the Recording its Started made, or an end before it.
    private val startSignal = MutableStateFlow<StartSignal?>(null)

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
            startAbandoned = false
            finalizeSeen = false
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
                    return@withLock it.also { backToIdle(id) }
                }
                engineCalled = true
                val started = withTimeoutOrNull(RECORDING_START_TIMEOUT) { confirmedStart(config, id) }
                outcome = started ?: run {
                    withContext(NonCancellable) { stopUnconfirmed(id, "timed out") }
                    CameraKResult.Failure(CameraKError.Timeout(CameraOperation.RECORD_START))
                }
                outcome
            } finally {
                // A throwable or close's cancellation: the start ends with no recording left running.
                if (outcome == null) {
                    withContext(NonCancellable) {
                        if (engineCalled) stopUnconfirmed(id, "was abandoned") else backToIdle(id)
                    }
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
            // The engine started nothing, so no Finalized is owed.
            is CameraKResult.Failure -> return started.also { backToIdle(id) }
            is CameraKResult.Success -> started.value
        }
        return when (val signal = startSignal.filterNotNull().first()) {
            // The recording started; the platform may have ended it since, which Finished and RecordingEnded say.
            is StartSignal.Started -> CameraKResult.Success(signal.recording)
            is StartSignal.Ended -> endedBeforeStarted(id, path, signal.finalized)
        }
    }

    // A Finalized before any Started fails the start, unless it left a playable file (§5, partial files).
    private fun endedBeforeStarted(
        id: Long,
        path: String,
        finalized: EngineRecordingEvent.Finalized,
    ): CameraKResult<RecordingState.Recording> = when (val result = finalized.result) {
        is CameraKResult.Failure -> result.also { backToIdle(id) }
        is CameraKResult.Success -> {
            val recording = RecordingState.Recording(
                id,
                path,
                timeSource.markNow(),
                Duration.ZERO,
                result.value.hasAudio,
            )
            val end = synchronized(lock) {
                if (_state.value is RecordingState.Starting && startingId == id) {
                    finishLocked(id, result, finalized.reason)
                } else {
                    CameraKLogger.w(TAG, "A recording start ended after its state had moved on; the state stays")
                    null
                }
            }
            end?.let(::announce)
            CameraKResult.Success(recording)
        }
    }

    // A start that was not confirmed may still be running on the platform: stop it, and go back to Idle.
    // The platform began nothing only when it answers that there is nothing to stop; anything else
    // (a file, another failure, no answer) means that recording still owes its Finalized.
    private suspend fun stopUnconfirmed(id: Long, why: String) {
        synchronized(lock) { startAbandoned = true }
        CameraKLogger.w(TAG, "A recording start $why; stopping what the platform began")
        val stopped = withTimeoutOrNull(SHUTDOWN_STOP_TIMEOUT) { engineStop() }
        when (stopped) {
            null -> CameraKLogger.w(TAG, "The stop of an unconfirmed recording start did not answer in time")
            is CameraKResult.Success -> CameraKLogger.d(
                TAG,
                "The stop of an unconfirmed recording start returned a file",
            )
            is CameraKResult.Failure -> CameraKLogger.d(
                TAG,
                "The stop of an unconfirmed recording start returned ${stopped.error::class.simpleName}",
            )
        }
        val nothingBegan = stopped is CameraKResult.Failure && stopped.error is CameraKError.InvalidState
        synchronized(lock) {
            if (!nothingBegan && !finalizeSeen) owedFinalizes++
        }
        backToIdle(id)
    }

    private suspend fun engineStop(): CameraKResult<RecordedVideo> = suspendRunCatching {
        engine.stopRecording()
    }.getOrElse { e ->
        CameraKLogger.e(TAG, "Stopping the recording threw; it ends as Internal", e)
        CameraKResult.Failure(CameraKError.Internal(e))
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
            withTimeoutOrNull(SHUTDOWN_STOP_TIMEOUT) { engineStop() }
        } catch (e: CancellationException) {
            // Only the holder's own end cancels a stop; the recording still ends, without its file.
            finishStop(stopping, CameraKResult.Failure(CameraKError.Closed), reason)
            throw e
        }
        if (result == null) {
            // The platform may still finalize it; that Finalized is owed, and drained when it comes.
            CameraKLogger.e(
                TAG,
                "Stopping the recording timed out; it ends as Timeout",
                RecordingStopTimeoutException(SHUTDOWN_STOP_TIMEOUT),
            )
            val timedOut = CameraKResult.Failure(CameraKError.Timeout(CameraOperation.RECORD_STOP))
            finishStop(stopping, timedOut, RecordingEndReason.ERROR)
            return@withLock timedOut
        }
        finishStop(stopping, result, reason)
        result
    }

    // The stop's end, only if the recording is still the Stopping this stop wrote.
    private fun finishStop(
        stopping: RecordingState.Stopping,
        result: CameraKResult<RecordedVideo>,
        reason: RecordingEndReason,
    ) {
        val end = synchronized(lock) {
            if (_state.value !== stopping) {
                CameraKLogger.w(TAG, "A recording stop returned after the recording had ended; its outcome is dropped")
                return
            }
            if (!finalizeSeen) owedFinalizes++
            finishLocked(stopping.recordingId, result, reason)
        }
        announce(end)
    }

    private fun onEngineEvent(event: EngineRecordingEvent) {
        when (event) {
            // A Started is always the current start's: only Finalized events can arrive late.
            is EngineRecordingEvent.Started -> {
                val recording = synchronized(lock) {
                    if (_state.value !is RecordingState.Starting || startAbandoned) return
                    becomeRecording(startingId, event.path, event.hasAudio).also {
                        startSignal.value = StartSignal.Started(it)
                    }
                }
                @Suppress("DEPRECATION")
                emit(CameraKEvent.RecordingStarted(recording.filePath))
            }
            is EngineRecordingEvent.Finalized -> onFinalized(event)
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

    // The oldest owed Finalized is drained; otherwise it belongs to the recording live now.
    private fun onFinalized(event: EngineRecordingEvent.Finalized) {
        val end = synchronized(lock) {
            if (owedFinalizes > 0) {
                owedFinalizes--
                CameraKLogger.d(TAG, "A recording that had already ended reported its finalization; it is drained")
                return
            }
            when (val current = _state.value) {
                is RecordingState.Starting -> {
                    finalizeSeen = true
                    startSignal.value = StartSignal.Ended(event)
                    null
                }
                is RecordingState.Recording -> {
                    finalizeSeen = true
                    finishLocked(current.recordingId, event.result, event.reason)
                }
                is RecordingState.Paused -> {
                    finalizeSeen = true
                    finishLocked(current.recordingId, event.result, event.reason)
                }
                // The stop holds the transition; its own return is the outcome.
                is RecordingState.Stopping -> {
                    finalizeSeen = true
                    null
                }
                RecordingState.Idle, is RecordingState.Finished -> {
                    CameraKLogger.w(TAG, "A recording finalization arrived with no recording live; it is dropped")
                    null
                }
            }
        }
        end?.let(::announce)
    }

    // Any end, under lock: Finished for display. The caller announces it once the lock is released.
    private fun finishLocked(id: Long, result: CameraKResult<RecordedVideo>, reason: RecordingEndReason): Ended {
        val filePath = when (val current = _state.value) {
            is RecordingState.Recording -> current.filePath
            is RecordingState.Paused -> current.filePath
            is RecordingState.Stopping -> current.filePath
            else -> null
        }
        val recordedMs = clock?.recordedMillis(nowNanos()) ?: 0L
        stopTicking()
        val finished = RecordingState.Finished(id, result, reason).also { _state.value = it }
        return Ended(finished, filePath, recordedMs)
    }

    // RecordingEnded unless the caller asked for the end, then the deprecated 1.x events.
    private fun announce(end: Ended) {
        val finished = end.finished
        if (finished.reason != RecordingEndReason.USER) {
            emit(CameraKEvent.RecordingEnded(finished.recordingId, finished.result, finished.reason))
        }
        announceLegacy(end)
    }

    @Suppress("DEPRECATION")
    private fun announceLegacy(end: Ended) {
        val limit = end.finished.reason == RecordingEndReason.MAX_DURATION
        when (val result = end.finished.result) {
            is CameraKResult.Success -> {
                val video = result.value
                val durationMs = video.duration.inWholeMilliseconds
                if (limit) emit(CameraKEvent.RecordingMaxDurationReached(video.path, durationMs))
                emit(CameraKEvent.RecordingStopped(VideoCaptureResult.Success(video.path, durationMs)))
            }
            is CameraKResult.Failure -> {
                if (limit) end.filePath?.let { emit(CameraKEvent.RecordingMaxDurationReached(it, end.recordedMs)) }
                emit(CameraKEvent.RecordingFailed(RecordingFailedException.of(result.error)))
            }
        }
    }

    // Only from this start's own Starting, or the Recording a Started made after it gave up.
    private fun backToIdle(id: Long) = synchronized(lock) {
        val ours = when (val current = _state.value) {
            is RecordingState.Starting -> startingId == id
            is RecordingState.Recording -> current.recordingId == id
            else -> false
        }
        if (!ours) {
            CameraKLogger.w(TAG, "A recording start ended after its state had moved on; the state stays")
            return@synchronized
        }
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

    // A Finished, with what the 1.x events need of the recording it ended.
    private class Ended(val finished: RecordingState.Finished, val filePath: String?, val recordedMs: Long)

    private sealed interface StartSignal {
        class Started(val recording: RecordingState.Recording) : StartSignal

        class Ended(val finalized: EngineRecordingEvent.Finalized) : StartSignal
    }

    private companion object {
        const val TAG = "CameraK"
    }
}

/**
 * The exception a deprecated [CameraKEvent.RecordingFailed] carries for an error with no exception of
 * its own; it names the error's kind, never a path.
 */
internal class RecordingFailedException(error: CameraKError) :
    IllegalStateException("The recording failed: ${error::class.simpleName}", error.cause) {
    companion object {
        /** The platform's own exception when there is one, as 1.x passed it on. */
        fun of(error: CameraKError): Exception = error.cause as? Exception ?: RecordingFailedException(error)
    }
}

/** The recording did not finalize within [SHUTDOWN_STOP_TIMEOUT]; logged, never thrown. */
internal class RecordingStopTimeoutException(timeout: Duration) :
    IllegalStateException("The recording did not finalize within $timeout")

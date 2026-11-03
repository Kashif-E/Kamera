@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.state

import com.kashif.cameraK.coroutines.suspendRunCatching
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.CameraKPermissionChecker
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long a start may wait for the platform to open, hold or fail the camera before the holder
 * gives up with `Timeout(START)`: long enough for a cold CameraX bind or the macOS permission
 * prompt's first frame, short enough that a camera that never answers does not leave a spinner.
 */
internal val START_TIMEOUT: Duration = 10.seconds

/**
 * How long `close()` waits for the engine to release the camera. A platform that never returns
 * cannot keep the holder, or the next holder waiting on the same device, open beyond it.
 */
internal val ENGINE_CLOSE_TIMEOUT: Duration = 5.seconds

/**
 * The state a start that timed out leaves: `Timeout(START)`, or `Permission(NotDetermined)` while
 * the checker still reports NotDetermined, because a platform that prompts on open (macOS) is still
 * waiting for the user's answer, and the re-check on resume then starts it.
 */
internal fun startTimeoutState(status: PermissionStatus): CameraKState.Error = if (status ==
    PermissionStatus.NotDetermined
) {
    CameraKState.Error(CameraKError.Permission(CameraKPermission.CAMERA, PermissionStatus.NotDetermined))
} else {
    CameraKState.Error(CameraKError.Timeout(CameraOperation.START))
}

/**
 * The holder's session lifecycle: a desired lifecycle (stopped, running, closed) that one
 * reconciler, started at construction in [scope], turns into engine calls, and the holder's
 * [state], written only through [transition]. A new desired value cancels the work of the previous
 * one, so a stop during a start cancels the start instead of racing it.
 *
 * @param beforeReady Runs before the first and every later Ready is published, outside any lock.
 */
internal class SessionLifecycle(
    private val engine: CameraEngine,
    private val permissionChecker: CameraKPermissionChecker?,
    scope: CoroutineScope,
    private val readyState: CameraKState.Ready,
    private val desiredHardware: () -> DesiredHardware,
    private val beforeReady: () -> Unit,
) {
    private sealed interface Desired {
        /** Stopped once every attempt up to [upTo] had been requested. */
        data class Stopped(val upTo: Int) : Desired

        /** [attempt] changes on every start from Stopped or Error, so the reconciler runs a new session. */
        class Running(val attempt: Int) : Desired

        data object Closed : Desired
    }

    /** What the session of [attempt] left once it was no longer Starting. */
    private class Settled(val attempt: Int, val state: CameraKState)

    private val desired = MutableStateFlow<Desired>(Desired.Stopped(upTo = 0))
    private val _state = MutableStateFlow<CameraKState>(CameraKState.Idle)
    val state: StateFlow<CameraKState> = _state.asStateFlow()

    private val attempts = atomic(0)
    private val settled = MutableStateFlow(Settled(0, CameraKState.Idle))
    private val publishing = Mutex()

    // Read and written by the reconciler only, one desired value at a time.
    private var engineStarted = false

    private val reconciler: Job = scope.launch {
        desired.collectLatest { d ->
            when (d) {
                is Desired.Stopped -> {
                    if (engineStarted) withContext(NonCancellable) { engine.stop() }
                    transition { CameraKState.Idle }
                    settled.value = Settled(d.upTo, CameraKState.Idle)
                }
                is Desired.Running -> runSessionCatching(d.attempt)
                // The holder's release owns shutdown.
                Desired.Closed -> Unit
            }
        }
    }

    /** The only writer of [state]. Nothing leaves Closed, whatever the engine reports. */
    fun transition(next: (CameraKState) -> CameraKState) {
        _state.update { current -> if (current is CameraKState.Closed) current else next(current) }
    }

    /**
     * Starts a session from Idle or Error; changes nothing while one is live. Returns the attempt
     * whose outcome [awaitSettled] waits for, or 0 once closed.
     */
    fun start(): Int {
        var target = 0
        desired.update { d ->
            when {
                d is Desired.Closed -> d.also { target = 0 }
                d is Desired.Stopped || _state.value is CameraKState.Error ->
                    Desired.Running(attempts.incrementAndGet()).also { target = it.attempt }
                else -> d.also { target = (d as Desired.Running).attempt }
            }
        }
        return target
    }

    fun stop() {
        desired.update { if (it is Desired.Closed) it else Desired.Stopped(attempts.value) }
    }

    /** Restarts from Error; false in any other state. */
    fun retry(): Boolean {
        if (_state.value !is CameraKState.Error) return false
        return restart()
    }

    /**
     * Starts again from `Error(Permission)` whose status is not Restricted, once the checker reports
     * a status that allows opening. Does nothing in any other state.
     */
    fun recheckPermission() {
        val error = (_state.value as? CameraKState.Error)?.error as? CameraKError.Permission ?: return
        if (error.status == PermissionStatus.Restricted) return
        val checker = permissionChecker ?: return
        val allowed = try {
            checker.allowsOpen(checker.status(CameraKPermission.CAMERA))
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Re-checking the camera permission failed; the holder stays in Error", e)
            return
        }
        if (allowed) restart()
    }

    private fun restart(): Boolean {
        var wrote = false
        desired.update { d ->
            if (d is Desired.Closed) {
                d
            } else {
                Desired.Running(attempts.incrementAndGet()).also { wrote = true }
            }
        }
        return wrote
    }

    /**
     * The state the session of [attempt] settled in once it left Starting: Ready, Interrupted, Error,
     * Idle after a stop, or Closed.
     */
    suspend fun awaitSettled(attempt: Int): CameraKState = combine(settled, _state) { s, current ->
        when {
            current is CameraKState.Closed -> current
            s.attempt >= attempt -> s.state
            else -> null
        }
    }.filterNotNull().first()

    /** Publishes Closed. From here [transition] keeps it. */
    fun enterClosed() {
        _state.value = CameraKState.Closed
    }

    /** Ends the reconciler: a session still starting stops the engine, a live one is left to the engine's close. */
    suspend fun shutDown() {
        desired.value = Desired.Closed
        reconciler.cancelAndJoin()
    }

    /** A failure outside a session's own handling ends the session in `Error(Internal)`. */
    fun failSession(error: Throwable) {
        transition { current ->
            when (current) {
                CameraKState.Starting, is CameraKState.Ready, is CameraKState.Interrupted ->
                    CameraKState.Error(CameraKError.Internal(error))
                else -> current
            }
        }
    }

    private suspend fun runSessionCatching(attempt: Int) {
        suspendRunCatching { runSession(attempt) }.onFailure { e ->
            CameraKLogger.e(TAG, "The camera session failed unexpectedly", e)
            withContext(NonCancellable) { engine.stop() }
            transition { CameraKState.Error(CameraKError.Internal(e)) }
            settle(attempt)
        }
    }

    private suspend fun runSession(attempt: Int) {
        transition { CameraKState.Starting }
        val checker = permissionChecker
        val status = checker?.status(CameraKPermission.CAMERA) ?: PermissionStatus.Granted
        if (checker != null && !checker.allowsOpen(status)) {
            transition { CameraKState.Error(CameraKError.Permission(CameraKPermission.CAMERA, status)) }
            settle(attempt)
            return
        }
        engineStarted = true
        // Open or Interrupted was reported for this session: the platform accepted it.
        var accepted = false
        try {
            coroutineScope {
                val runtimeFailure = CompletableDeferred<Unit>()
                // UNDISPATCHED subscribes before start is called, and drop(1) discards the value the
                // engine held before this session: its first Closed, or what a stopped session left.
                // A Failed ends the mirror: it ends its generation, so no later value of it is mapped.
                val mirror = launch(start = CoroutineStart.UNDISPATCHED) {
                    engine.state.drop(1).first { s ->
                        if (s is EngineState.Open || s is EngineState.Interrupted) accepted = true
                        publish(attempt) { s }
                        s is EngineState.Failed
                    }
                    runtimeFailure.complete(Unit)
                }
                when (val started = withTimeoutOrNull(START_TIMEOUT) { engine.start(desiredHardware()) }) {
                    null -> {
                        mirror.cancel()
                        withContext(NonCancellable) { engine.stop() }
                        transition { startTimeoutState(status) }
                        settle(attempt)
                    }
                    is CameraKResult.Failure -> {
                        // Authoritative whether or not the engine also published Failed.
                        mirror.cancel()
                        withContext(NonCancellable) { engine.stop() }
                        transition { CameraKState.Error(started.error) }
                        settle(attempt)
                    }
                    is CameraKResult.Success -> {
                        // Success means Open or Interrupted. Mapping the current value covers a start
                        // on an engine that was already open, whose state therefore never changed.
                        accepted = true
                        publish(attempt) { engine.state.value }
                        // The session lives until the next desired value cancels it, or until the
                        // platform fails it: then it releases the camera, so that no later engine
                        // value, such as a platform reopening on its own, moves Error back to Ready.
                        runtimeFailure.await()
                        withContext(NonCancellable) { engine.stop() }
                    }
                }
            }
        } catch (e: CancellationException) {
            // A cancelled start leaves nothing open. An accepted, live session is left to what
            // cancelled it: a stop (which stops the engine) or close (which closes it).
            if (!accepted) withContext(NonCancellable) { engine.stop() }
            throw e
        }
    }

    // The mirror and a successful start both publish what the engine reports, on any thread. One at a
    // time, so neither settles Ready while the other is still attaching the plugins before it.
    private suspend fun publish(attempt: Int, read: () -> EngineState) = publishing.withLock {
        val engineState = read()
        if (engineState is EngineState.Open && _state.value !is CameraKState.Closed) beforeReady()
        transition { current -> engineState.toCameraKState(current, readyState) }
        settle(attempt)
    }

    private fun settle(attempt: Int) {
        val current = _state.value
        if (current != CameraKState.Starting) settled.value = Settled(attempt, current)
    }

    private companion object {
        const val TAG = "CameraK"
    }
}

package com.kashif.cameraK.state

import com.kashif.cameraK.coroutines.suspendRunCatching
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long `close()` lets commands that are finishing normally land (a capture writing its file)
 * before it closes the engine, which completes whatever is still pending with `Failure(Closed)`.
 */
internal val COMMAND_DRAIN_TIMEOUT: Duration = 2.seconds

/** More captures than this in flight at once return `Failure(Busy(CAPTURE))`. */
internal const val MAX_IN_FLIGHT_CAPTURES: Int = 3

/**
 * Runs the holder's commands. Each command checks [refusal] first, then runs as a job in the
 * holder's [scope], so a caller that is cancelled stops waiting while the platform operation
 * finishes: the file is finalised and every counter is settled. A throwable is mapped to
 * `Internal`, and every failure is logged once with the operation's name, never with its subject.
 * `close()` waits for the jobs through [drain] and [joinAll].
 *
 * @param refusal The error a command gets without running: `Closed` after close, `NotReady`
 *   outside Ready; null when commands may run.
 */
internal class CommandRunner(private val scope: CoroutineScope, private val refusal: () -> CameraKError?) {
    private val lock = SynchronizedObject()
    private val jobs = mutableSetOf<Job>()

    /** The failure a command returns without running, or null when it may run. */
    fun refused(): CameraKResult.Failure? = refusal()?.let { CameraKResult.Failure(it) }

    /** Checks [refused], then runs [block] as a command job and waits for its result. */
    suspend fun <T> run(operation: CameraOperation, block: suspend () -> CameraKResult<T>): CameraKResult<T> {
        refused()?.let { return it }
        return await(start(operation, block))
    }

    /**
     * Starts [block] as a command job. The job's body always runs, even when the holder's scope is
     * already cancelled, so a `finally` in it settles what the command took.
     */
    fun <T> start(operation: CameraOperation, block: suspend () -> CameraKResult<T>): Deferred<CameraKResult<T>> =
        scope.async(start = CoroutineStart.ATOMIC) { execute(operation, block) }.also(::track)

    /** Starts [block] as a command job with no result of its own; the body always runs, as in [start]. */
    fun launch(block: suspend () -> Unit): Job = scope.launch(start = CoroutineStart.ATOMIC) { block() }.also(::track)

    /** Waits for [job]. A job that close cancelled under a caller still waiting is `Failure(Closed)`. */
    suspend fun <T> await(job: Deferred<CameraKResult<T>>): CameraKResult<T> = try {
        job.await()
    } catch (e: CancellationException) {
        // The caller's own cancellation goes on; only the job's comes back as a result.
        currentCoroutineContext().ensureActive()
        if (!job.isCancelled) throw e
        CameraKLogger.w(TAG, "A command ended with the holder's scope; it returns Closed", e)
        CameraKResult.Failure(CameraKError.Closed)
    }

    /** Runs [block] in the current job: a throwable becomes `Internal`, and a failure is logged once. */
    suspend fun <T> execute(operation: CameraOperation, block: suspend () -> CameraKResult<T>): CameraKResult<T> {
        val result = suspendRunCatching { block() }.getOrElse { e ->
            CameraKLogger.e(TAG, "The $operation command threw; it returns Internal", e)
            return CameraKResult.Failure(CameraKError.Internal(e))
        }
        if (result is CameraKResult.Failure) {
            CameraKLogger.w(TAG, "The $operation command failed: ${result.error::class.simpleName}", result.error.cause)
        }
        return result
    }

    /** Waits at most [timeout] for the command jobs running now. */
    suspend fun drain(timeout: Duration) {
        withTimeoutOrNull(timeout) { joinAll() }
    }

    /** Waits for the command jobs running now. */
    suspend fun joinAll() {
        synchronized(lock) { jobs.toList() }.joinAll()
    }

    private fun track(job: Job) {
        synchronized(lock) { jobs += job }
        job.invokeOnCompletion { synchronized(lock) { jobs -= job } }
    }

    private companion object {
        const val TAG = "CameraK"
    }
}

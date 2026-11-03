package com.kashif.cameraK.state

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred

/**
 * A latest-wins conflator for one gesture-rate command (Decision 9): at most one [call] in flight
 * and one pending. A request made while another is pending replaces its argument, and every caller
 * of the pending request shares one result: the replacing call's. No superseded caller fails or is
 * logged.
 *
 * @param launch Starts the worker as a command job in the holder's scope; its body must always run,
 *   even in a scope that is already cancelled, so that no caller is left waiting.
 * @param call The engine command. It maps its own failures into its result.
 * @param abandoned The result for callers whose request never ran because the worker was cancelled.
 */
internal class LatestWins<A, R>(
    private val launch: (suspend () -> Unit) -> Unit,
    private val call: suspend (A) -> R,
    private val abandoned: () -> R,
) {
    private class Request<A, R>(var argument: A) {
        val result = CompletableDeferred<R>()
    }

    private val lock = SynchronizedObject()
    private var pending: Request<A, R>? = null
    private var running = false

    /** Runs [argument] once the call in flight returns, unless a later request replaces it first. */
    suspend fun submit(argument: A): R {
        var startWorker = false
        val request = synchronized(lock) {
            val joined = pending?.also { it.argument = argument } ?: Request<A, R>(argument).also { pending = it }
            if (!running) {
                running = true
                startWorker = true
            }
            joined
        }
        if (startWorker) launch { work() }
        return request.result.await()
    }

    private suspend fun work() {
        var current: Request<A, R>? = null
        try {
            while (true) {
                val next = synchronized(lock) {
                    val taken = pending
                    pending = null
                    if (taken == null) running = false
                    taken
                } ?: return
                current = next
                // Off the pending slot, so nothing writes its argument any more.
                next.result.complete(call(next.argument))
                current = null
            }
        } finally {
            val unfinished = current
            if (unfinished != null) {
                val waiting = synchronized(lock) {
                    running = false
                    pending.also { pending = null }
                }
                unfinished.result.complete(abandoned())
                waiting?.result?.complete(abandoned())
            }
        }
    }
}

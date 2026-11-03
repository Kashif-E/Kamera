package com.kashif.cameraK.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.time.Duration

/**
 * The engine's platform-free collaborators. Each member has a default that needs no platform code,
 * so the platform slices read it and never edit `engine/`. Tests set it with
 * `environment.runtime = EngineRuntime(osVersion = 15)` and the like.
 */
internal class EngineRuntime(
    /**
     * A dispatcher on one dedicated daemon thread named after its argument: the desktop producer
     * (one per start) and each asynchronous analyzer's worker. The caller closes it when its work
     * ends.
     */
    val newWorker: (name: String) -> WorkerDispatcher = ::newDedicatedWorker,
    /** Delay before each reopen attempt after a lost device. */
    val backoff: BackoffPolicy = BackoffPolicy.Default,
    /** Null: the engine builds its platform tracer in its own files. Tests pass a recording one. */
    val tracer: Tracer? = null,
    /** Null: the running OS. Read only by the iOS engine; tests set 15, 17 or 18. */
    val osVersion: Int? = null,
) {
    companion object {
        val Default: EngineRuntime = EngineRuntime()
    }
}

/** A dispatcher plus what releases its thread. Tests wrap a test dispatcher and pass no release. */
internal class WorkerDispatcher(val dispatcher: CoroutineDispatcher, private val release: () -> Unit = {}) :
    AutoCloseable {
    override fun close() = release()
}

internal fun newDedicatedWorker(name: String): WorkerDispatcher = TODO("S2' red: dedicated worker for $name")

internal fun interface BackoffPolicy {
    /** [attempt] counts from 1. */
    fun delayBefore(attempt: Int): Duration

    companion object {
        /**
         * 250 ms, doubling, capped at 4 s. The engine waits with delay() on its own dispatcher, so
         * tests run it on virtual time; the recovery timeout, not the policy, decides when it gives up.
         */
        val Default: BackoffPolicy = BackoffPolicy { TODO("S2' red: default backoff") }
    }
}

internal interface Tracer {
    /** Opens an interval; closing the result ends it. */
    fun beginInterval(name: String): AutoCloseable

    /** A point event. */
    fun marker(name: String)

    companion object {
        val None: Tracer = object : Tracer {
            override fun beginInterval(name: String): AutoCloseable = AutoCloseable { }

            override fun marker(name: String) = Unit
        }
    }
}

internal object TraceNames {
    const val START = "CameraK.start"
    const val FIRST_FRAME = "CameraK.firstFrame"
    const val LENS_SWITCH = "CameraK.lensSwitch"
    const val CAPTURE = "CameraK.capture"
}

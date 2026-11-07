package com.kashif.cameraK.controller

import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.MainCoroutineDispatcher
import java.util.concurrent.ThreadFactory

private const val TAG = "CameraK"

/**
 * The form of [dispatcher] that runs at once when already on its thread, so a call made on the main
 * thread takes effect before it returns. A dispatcher with no immediate form, such as a test
 * dispatcher, is used as it is.
 */
internal fun immediateFormOf(dispatcher: CoroutineDispatcher): CoroutineDispatcher {
    val main = dispatcher as? MainCoroutineDispatcher ?: return dispatcher
    return try {
        main.immediate
    } catch (e: UnsupportedOperationException) {
        CameraKLogger.w(TAG, "The main dispatcher has no immediate form; dispatching every call", e)
        main
    }
}

/** Daemon threads named `[name]-1`, `[name]-2`, …, for the engine's executors. */
internal fun cameraKThreads(name: String): ThreadFactory {
    val count = atomic(0)
    return ThreadFactory { runnable ->
        Thread(runnable, "$name-${count.incrementAndGet()}").apply { isDaemon = true }
    }
}

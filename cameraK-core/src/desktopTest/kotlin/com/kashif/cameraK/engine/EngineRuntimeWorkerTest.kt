package com.kashif.cameraK.engine

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The default worker is one named daemon thread, which closing the worker ends. */
class EngineRuntimeWorkerTest {
    @Test
    fun defaultWorkerRunsOnOneNamedThreadAndStopsOnClose() = runBlocking {
        val worker = EngineRuntime.Default.newWorker("CameraK-test-worker")
        val first = withContext(worker.dispatcher) { Thread.currentThread() }
        val second = withContext(worker.dispatcher) { Thread.currentThread() }

        assertSame(first, second, "two tasks ran on two threads")
        assertEquals("CameraK-test-worker", first.name)
        assertTrue(first.isDaemon, "the worker thread would keep the JVM alive")

        worker.close()
        first.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse(first.isAlive, "the worker thread outlived close()")
    }
}

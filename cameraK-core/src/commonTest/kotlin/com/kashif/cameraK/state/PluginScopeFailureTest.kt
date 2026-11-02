package com.kashif.cameraK.state

import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A plugin coroutine that throws must not reach the uncaught-exception handler (on Android that
 * kills the host app). The failure is contained in [CameraKStateHolder.pluginScope], logged with its
 * throwable, and reported as [CameraKEvent.PluginFailed].
 */
class PluginScopeFailureTest {
    private val logged = mutableListOf<Pair<String, Throwable?>>()

    @AfterTest
    fun restoreLogger() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun throwingPluginCoroutine_isContainedLoggedAndReportedAsPluginFailed() = runTest {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { _, _, message, throwable -> logged += message to throwable }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val hostJob = Job()
        val hostScope = CoroutineScope(dispatcher + hostJob)
        val holder = CameraKStateHolder(
            cameraConfiguration = CameraConfiguration(),
            controllerFactory = { error("unit tests have no camera") },
            coroutineScope = hostScope,
        )
        val events = mutableListOf<CameraKEvent>()
        backgroundScope.launch(dispatcher) { holder.events.collect { events += it } }
        val otherPlugin = holder.pluginScope.launch { awaitCancellation() }
        val failure = IllegalStateException("plugin failed")

        holder.pluginScope.launch { throw failure }

        try {
            assertTrue(hostJob.isActive, "a plugin failure cancelled the host scope")
            assertTrue(otherPlugin.isActive, "a plugin failure cancelled another plugin's coroutine")
            val reported = events.filterIsInstance<CameraKEvent.PluginFailed>()
            assertEquals(1, reported.size, "the failure was not reported as PluginFailed: $events")
            assertSame(failure, reported.single().error)
            assertTrue(logged.any { (_, error) -> error === failure }, "the failure was not logged: $logged")
        } finally {
            hostScope.cancel()
        }
    }
}

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kashif.cameraK.plugin

import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Each attachment has its own scope and its own `failure`. A plugin that throws, in `onAttach` or
 * in a coroutine of its scope, is marked failed through its context and reported once as
 * `PluginFailed` with its id; the camera and the other plugins carry on.
 */
class PluginContainmentTest {
    private val logged = mutableListOf<Throwable?>()

    @AfterTest
    fun restoreLogger() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    private fun captureLogs() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { _, _, _, throwable -> logged += throwable }
    }

    @Test
    fun throwingOnAttachMarksPluginFailedAndEmitsPluginFailed() = runTest {
        captureLogs()
        val thrown = IllegalStateException("plugin attach failed")
        val thrower = ContextPlugin(id = "thrower") { throw thrown }
        val healthy = ContextPlugin(id = "healthy")
        val s = pluginSession(thrower, healthy)

        startReady(s)

        val failure = assertIs<CameraKError.Internal>(thrower.context.failure.value, "the thrower's failure")
        assertSame(thrown, failure.cause)
        assertNull(healthy.context.failure.value, "the healthy plugin's failure")
        val reported = s.pluginFailures.single()
        assertEquals("thrower", reported.pluginId, "PluginFailed names the plugin by its id")
        assertSame(failure, reported.error, "the event carries the context's failure")
        assertTrue(logged.any { it === thrown }, "the throwable was not logged: $logged")
    }

    @Test
    fun throwingInPluginScopeMarksFailedAndOtherPluginsRun() = runTest {
        captureLogs()
        val thrown = IllegalStateException("plugin coroutine failed")
        var otherJob: Job? = null
        val thrower = ContextPlugin(id = "thrower") { it.scope.launch { throw thrown } }
        val other = ContextPlugin(id = "other") { otherJob = it.scope.launch { awaitCancellation() } }
        val s = pluginSession(thrower, other)

        startReady(s)

        val failure = assertIs<CameraKError.Internal>(thrower.context.failure.value, "the thrower's failure")
        assertSame(thrown, failure.cause)
        assertNull(other.context.failure.value, "the other plugin's failure")
        assertTrue(otherJob?.isActive == true, "the other plugin's coroutine was cancelled")
        assertTrue(other.context.scope.isActive, "the other plugin's scope was cancelled")
        assertIs<CameraKState.Ready>(s.holder.cameraState.value, "a plugin coroutine's throw reached the camera")
        val reported = s.pluginFailures.single()
        assertEquals("thrower", reported.pluginId)
        assertSame(failure, reported.error)
        assertTrue(logged.any { it === thrown }, "the throwable was not logged: $logged")
    }

    @Test
    fun reportFailureSetsFailedStatus() = runTest {
        val reportedError = CameraKError.Unsupported(CameraFeature.ANALYSIS_FORMAT)
        val plugin = ContextPlugin()
        val s = pluginSession(plugin)
        startReady(s)

        plugin.context.reportFailure(reportedError)

        assertSame(reportedError, plugin.context.failure.value)
    }

    @Test
    fun firstFailureWins() = runTest {
        val first = CameraKError.Unsupported(CameraFeature.ANALYSIS_FORMAT)
        val plugin = ContextPlugin(id = "twice")
        val s = pluginSession(plugin)
        startReady(s)

        plugin.context.reportFailure(first)
        plugin.context.reportFailure(CameraKError.Unsupported(CameraFeature.ZOOM))
        plugin.context.scope.launch { throw IllegalStateException("a later failure") }
        runCurrent()

        assertSame(first, plugin.context.failure.value, "a later failure replaced the first")
        assertSame(first, s.pluginFailures.single().error, "a failure after the first was reported again")
    }

    @Test
    fun reattachedPluginStartsWithoutAFailure() = runTest {
        val first = CameraKError.Unsupported(CameraFeature.ANALYSIS_FORMAT)
        val plugin = ContextPlugin()
        val s = pluginSession(plugin)
        startReady(s)
        val firstContext = plugin.context
        firstContext.reportFailure(first)

        s.holder.detachPlugin(plugin)
        s.holder.attachPlugin(plugin)

        assertEquals(2, plugin.contexts.size, "the plugin was not attached again")
        assertNotSame(firstContext, plugin.context, "a new attachment reused the old context")
        assertNull(plugin.context.failure.value, "the new attachment started failed")
        assertSame(first, firstContext.failure.value, "the old attachment's failure changed")
    }

    @Test
    fun detachCancelsThePluginsScopeAfterOnDetach() = runTest {
        var activeInOnDetach: Boolean? = null
        lateinit var context: CameraKPluginContext
        val plugin = object : ContextPlugin() {
            override fun onDetach() {
                activeInOnDetach = context.scope.isActive
            }
        }
        val s = pluginSession(plugin)
        startReady(s)
        context = plugin.context
        val job = context.scope.launch { awaitCancellation() }

        s.holder.detachPlugin(plugin)
        runCurrent()

        assertEquals(true, activeInOnDetach, "the scope was cancelled before onDetach ran")
        assertFalse(context.scope.coroutineContext.job.isActive, "the scope outlived the attachment")
        assertTrue(job.isCancelled, "a coroutine of the detached plugin kept running")
    }
}

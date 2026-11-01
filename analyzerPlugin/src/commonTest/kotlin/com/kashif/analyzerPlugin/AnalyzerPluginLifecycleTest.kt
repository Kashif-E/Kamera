package com.kashif.analyzerPlugin

import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyzerPluginLifecycleTest {
    @Test
    fun detach_cancelsTheReadyCollectorStartedByAttach() = runTest {
        val hostScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val holder = CameraKStateHolder(CameraConfiguration(), { error("unit tests have no camera") }, hostScope)
        val plugin = AnalyzerPlugin(hostScope)

        plugin.onAttach(holder)
        assertEquals(1, holder.activePluginJobs(), "attach should wait for Ready in pluginScope")

        plugin.onDetach()
        assertEquals(0, holder.activePluginJobs(), "onDetach must cancel the collector it launched")

        hostScope.cancel()
    }

    @Test
    fun startAnalyzer_beforeCameraIsReady_emitsNoFrames() = runTest {
        val plugin = AnalyzerPlugin(backgroundScope)
        val frames = mutableListOf<ByteArray>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            plugin.getAnalyzerFlow().collect {
                frames +=
                    it
            }
        }

        plugin.startAnalyzer()
        plugin.stopAnalyzer()

        assertTrue(frames.isEmpty(), "no controller, so no analyzer and no frames; got ${frames.size}")
    }
}

private fun CameraKStateHolder.activePluginJobs(): Int =
    checkNotNull(pluginScope.coroutineContext[Job]).children.count { it.isActive }

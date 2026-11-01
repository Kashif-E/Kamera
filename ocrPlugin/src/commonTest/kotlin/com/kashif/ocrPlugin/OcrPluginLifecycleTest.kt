package com.kashif.ocrPlugin

import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OcrPluginLifecycleTest {
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun detachThenReattach_cancelsTheCollectorAndKeepsTheTextChannelOpen() = runTest {
        val hostScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val holder = CameraKStateHolder(CameraConfiguration(), { error("unit tests have no camera") }, hostScope)
        val plugin = OcrPlugin(hostScope)

        plugin.onAttach(holder)
        assertEquals(1, holder.activePluginJobs(), "attach should wait for Ready in pluginScope")

        plugin.onDetach()
        assertEquals(0, holder.activePluginJobs(), "onDetach must cancel the collector it launched")
        assertFalse(plugin.ocrFlow.isClosedForSend, "ocrFlow must stay open so the plugin can be re-attached")

        plugin.onAttach(holder)
        assertEquals(1, holder.activePluginJobs(), "re-attach should start a fresh collector")

        plugin.onDetach()
        hostScope.cancel()
    }
}

private fun CameraKStateHolder.activePluginJobs(): Int =
    checkNotNull(pluginScope.coroutineContext[Job]).children.count { it.isActive }

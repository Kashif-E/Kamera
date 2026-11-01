package com.kashif.qrscannerplugin

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

class QRScannerPluginLifecycleTest {
    @Test
    fun detach_cancelsTheReadyCollectorStartedByAttach() = runTest {
        val hostScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val holder = CameraKStateHolder(CameraConfiguration(), { error("unit tests have no camera") }, hostScope)
        val plugin = QRScannerPlugin(hostScope)

        plugin.onAttach(holder)
        assertEquals(1, holder.activePluginJobs(), "attach should wait for Ready in pluginScope")

        plugin.onDetach()
        assertEquals(0, holder.activePluginJobs(), "onDetach must cancel the collector it launched")

        hostScope.cancel()
    }

    @Test
    fun resumeScanning_beforeCameraIsReady_emitsNothing() = runTest {
        val hostScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val plugin = QRScannerPlugin(hostScope)
        val codes = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            plugin.getQrCodeFlow().collect { codes += it }
        }

        plugin.resumeScanning()

        assertTrue(codes.isEmpty(), "no controller, so no scanner and no codes; got $codes")
        hostScope.cancel()
    }
}

private fun CameraKStateHolder.activePluginJobs(): Int =
    checkNotNull(pluginScope.coroutineContext[Job]).children.count { it.isActive }

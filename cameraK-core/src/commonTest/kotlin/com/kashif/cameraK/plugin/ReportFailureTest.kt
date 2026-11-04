package com.kashif.cameraK.plugin

import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.state.CameraKPlugin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** A plugin's own failure reaches the app as `PluginFailed`, named by the plugin's `id`. */
class ReportFailureTest {
    @Test
    fun reportFailureEmitsPluginFailedWithThePluginsId() = runTest {
        val error = CameraKError.Unsupported(CameraFeature.ANALYSIS_FORMAT)
        val plugin = ContextPlugin(id = "com.example.scanner")
        val s = pluginSession(plugin)
        startReady(s)

        plugin.context.reportFailure(error)

        val reported = s.pluginFailures.single()
        assertEquals("com.example.scanner", reported.pluginId)
        assertSame(error, reported.error)
    }

    private class NamedByItsClass : CameraKPlugin {
        override fun onDetach() = Unit
    }

    @Test
    fun theDefaultIdIsTheClassName() {
        assertEquals("NamedByItsClass", NamedByItsClass().id)
    }
}

@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.kashif.cameraK.plugin

import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The holder attaches every plugin with a context. A 1.x plugin, which implements only
 * `onAttach(stateHolder)`, still attaches: the context overload's default forwards to it.
 */
class LegacyOnAttachTest {
    private class UnmigratedPlugin : CameraKPlugin {
        val holders = mutableListOf<CameraKStateHolder>()

        override fun onAttach(stateHolder: CameraKStateHolder) {
            holders += stateHolder
        }

        override fun onDetach() = Unit
    }

    @Test
    fun unmigratedPluginStillAttaches() = runTest {
        val unmigrated = UnmigratedPlugin()
        val migrated = ContextPlugin()
        val s = pluginSession(unmigrated, migrated)

        startReady(s)

        assertEquals(1, migrated.contexts.size, "a migrated plugin's onAttach(context) calls")
        assertEquals(1, unmigrated.holders.size, "an unmigrated plugin's onAttach(stateHolder) calls")
        assertSame(s.holder, unmigrated.holders.single())

        // The default itself: a context the holder made forwards to its holder.
        unmigrated.onAttach(migrated.context)

        assertEquals(listOf(s.holder, s.holder), unmigrated.holders)
    }

    @Test
    fun aPluginOverridingNeitherOnAttachLogsOneWarningAtAttach() = runTest {
        val warnings = captureWarnings()
        val idle = object : CameraKPlugin {
            override fun onDetach() = Unit
        }
        val s = pluginSession(idle, UnmigratedPlugin(), ContextPlugin())

        startReady(s)

        assertEquals(1, warnings.count { "onAttach" in it }, "onAttach warnings at attach: $warnings")
    }

    private fun captureWarnings(): List<String> {
        val warnings = mutableListOf<String>()
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, _ -> if (level == CameraKLogger.Level.WARN) warnings += message }
        return warnings
    }

    @AfterTest
    fun restoreLogger() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }
}

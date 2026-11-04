package com.kashif.cameraK.plugin

import com.kashif.cameraK.error.InterruptionReason
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** `launchPerSession` runs its block once per Ready session and cancels it when the camera leaves Ready. */
class LaunchPerSessionTest {
    private class SessionPlugin : ContextPlugin() {
        var started = 0
        var cancelled = 0

        override fun onAttach(context: CameraKPluginContext) {
            super.onAttach(context)
            context.launchPerSession {
                started++
                try {
                    awaitCancellation()
                } finally {
                    cancelled++
                }
            }
        }
    }

    @Test
    fun blockCancelledWhenCameraLeavesReady() = runTest {
        val plugin = SessionPlugin()
        val s = pluginSession(plugin)
        startReady(s)
        assertEquals(1, plugin.started, "the block did not start in the Ready session")

        s.engine.interrupt(InterruptionReason.OTHER)
        runCurrent()

        assertEquals(1, plugin.cancelled, "the block outlived the Ready session")
    }

    @Test
    fun blockRestartsOnNextReady() = runTest {
        val plugin = SessionPlugin()
        val s = pluginSession(plugin)
        startReady(s)

        s.holder.stop()
        runCurrent()
        startReady(s)

        assertEquals(2, plugin.started, "the block did not start again at the next Ready")
        assertEquals(1, plugin.cancelled, "the first session's block was not cancelled")
    }
}

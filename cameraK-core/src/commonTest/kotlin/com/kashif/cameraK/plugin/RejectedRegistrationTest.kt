package com.kashif.cameraK.plugin

import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FrameSpec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A registration the session's format rejects never receives a frame, so the plugin is marked
 * failed through its context's `frames`, and reported once.
 */
class RejectedRegistrationTest {
    @Test
    fun unacceptedFormatMarksThePluginFailedOnce() = runTest {
        val plugin = ContextPlugin(id = "rgba-only")
        val s = pluginSession(plugin)
        startReady(s)

        val accepted = plugin.context.frames.register(FrameSpec(acceptedFormats = setOf(FrameFormat.YUV_420))) { }
        assertNull(accepted.rejection, "a YUV_420 session rejected a YUV_420 registration")
        assertNull(plugin.context.failure.value, "an accepted registration failed the plugin")

        val rejected = plugin.context.frames.register(FrameSpec(acceptedFormats = setOf(FrameFormat.RGBA_8888))) { }

        val rejection = assertIs<CameraKError.Unsupported>(assertNotNull(rejected.rejection, "the registration"))
        assertEquals(CameraFeature.ANALYSIS_FORMAT, rejection.feature)
        assertSame(rejection, plugin.context.failure.value, "the plugin's failure")
        val reported = s.pluginFailures.single()
        assertEquals("rgba-only", reported.pluginId)
        assertSame(rejection, reported.error)
    }
}

@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.plugin

import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Decision 14: a holder created with a plugin that needs frames binds frame analysis at start. The
 * derivation is `effectiveConfig`, which the holder passes to its engine factory.
 */
class NeedsFramesTest {
    private val config = CameraConfiguration {
        cameraLens = CameraLens.FRONT
        imageFormat = ImageFormat.PNG
    }

    /** The configuration the engine factory received for a holder created with [plugins]. */
    private fun TestScope.configReceived(plugins: List<CameraKPlugin>): CameraConfiguration {
        val received = mutableListOf<CameraConfiguration>()
        CameraKStateHolder(
            config,
            { c, _ ->
                received += c
                FakeCameraEngine()
            },
            plugins,
            cameraKTestEnvironment(),
        )
        return received.single()
    }

    @Test
    fun pluginAttachedAtCreationSetsFrameAnalysis() = runTest {
        val frames = ContextPlugin(id = "frames", needsFrames = true)
        val saver = ContextPlugin(id = "saver", needsFrames = false)

        assertEquals(config.copy { frameAnalysis = true }, configReceived(listOf(saver, frames)))
    }

    @Test
    fun saverOnlyLeavesFrameAnalysisOff() = runTest {
        val saver = ContextPlugin(id = "saver", needsFrames = false)

        assertEquals(config, configReceived(listOf(saver)))
        assertEquals(config, configReceived(emptyList()))
    }

    @Test
    fun effectiveConfigIsPure() {
        val frames = ContextPlugin(id = "frames", needsFrames = true)
        val saver = ContextPlugin(id = "saver", needsFrames = false)
        val appAsked = config.copy { frameAnalysis = true }

        assertEquals(config.copy { frameAnalysis = true }, effectiveConfig(config, listOf(frames)))
        assertEquals(effectiveConfig(config, listOf(frames)), effectiveConfig(config, listOf(frames)))
        assertSame(config, effectiveConfig(config, listOf(saver)), "nothing to derive, yet a new configuration")
        assertSame(appAsked, effectiveConfig(appAsked, listOf(saver)), "a saver turned the app's own frameAnalysis off")
        assertEquals(
            CameraConfiguration {
                cameraLens = CameraLens.FRONT
                imageFormat = ImageFormat.PNG
            },
            config,
        )
        assertTrue(
            frames.contexts.isEmpty() && saver.contexts.isEmpty(),
            "deriving the configuration attached a plugin",
        )
    }
}

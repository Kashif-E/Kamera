@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.plugin

import com.kashif.cameraK.frames.CameraFrame
import com.kashif.cameraK.testing.TestFrame
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * A context reads the holder's own flows and frame source and the holder's dispatchers; nothing
 * is copied per plugin. The placeholder recording state gets no test here: H3' wires and tests
 * the real one.
 */
class PluginContextSourcesTest {
    @Test
    fun contextReadsTheHoldersFlowsAndFrames() = runTest {
        val plugin = ContextPlugin()
        val s = pluginSession(plugin)
        startReady(s)
        val context = plugin.context

        assertSame(s.holder.cameraState, context.cameraState, "cameraState")
        assertSame(s.holder.uiState, context.uiState, "uiState")
        assertSame(s.holder.capabilities, context.capabilities, "capabilities")
        assertSame(s.holder.deviceOrientation, context.deviceOrientation, "deviceOrientation")
        assertSame(s.holder.captures, context.captures, "captures")

        val received = mutableListOf<CameraFrame>()
        val registration = context.frames.register { received += it }
        val frame = TestFrame.luma(2, 2, ByteArray(4))
        s.engine.fakeFrames.emit(frame)

        assertEquals(listOf<CameraFrame>(frame), received, "a frame the engine's source emitted")
        registration.close()
        s.engine.fakeFrames.emit(frame)
        assertEquals(1, received.size, "a closed registration still received frames")
    }

    @Test
    fun contextDispatchersAreTheEnvironments() = runTest {
        val plugin = ContextPlugin()
        val s = pluginSession(plugin)
        startReady(s)

        assertSame(s.environment.dispatchers, plugin.context.dispatchers)
    }
}

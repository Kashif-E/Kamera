@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.frames

import dev.kashifmehmood.camerak.state.CameraConfiguration
import dev.kashifmehmood.camerak.state.CameraKStateHolder
import dev.kashifmehmood.camerak.testing.FakeCameraEngine
import dev.kashifmehmood.camerak.testing.cameraKTestEnvironment
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertSame

/** An app's own analysis registers on the holder's `frames`: the engine's one frame source, not a copy. */
class HolderFramesTest {
    @Test
    fun holderFramesIsTheEnginesFrameSource() = runTest {
        val engine = FakeCameraEngine()
        val holder =
            CameraKStateHolder(CameraConfiguration(), { _, _ -> engine }, emptyList(), cameraKTestEnvironment())

        assertSame(engine.frames, holder.frames)
    }
}

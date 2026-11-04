@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.frames

import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
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

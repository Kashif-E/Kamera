@file:OptIn(CameraKEngineApi::class, InternalCameraKApi::class)

package dev.kashifmehmood.camerak.state

import dev.kashifmehmood.camerak.testing.FakeCameraEngine
import dev.kashifmehmood.camerak.testing.cameraKTestEnvironment
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import dev.kashifmehmood.camerak.utils.InternalCameraKApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertSame

/** `CameraPreview(holder)` reaches the engine's preview through the holder (design §7.1). */
class HolderPreviewSourceTest {
    @Test
    fun previewSourceIsTheEnginesPreview() = runTest {
        val fake = FakeCameraEngine()
        val holder = CameraKStateHolder(CameraConfiguration(), { _, _ -> fake }, emptyList(), cameraKTestEnvironment())

        assertSame(fake.preview, holder.previewSource)

        holder.close()
    }
}

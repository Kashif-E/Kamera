@file:OptIn(CameraKEngineApi::class, InternalCameraKApi::class)

package com.kashif.cameraK.state

import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.InternalCameraKApi
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

@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.engine.CameraSelection
import dev.kashifmehmood.camerak.engine.DesiredHardware
import dev.kashifmehmood.camerak.engine.EngineState
import dev.kashifmehmood.camerak.enums.AspectRatio
import dev.kashifmehmood.camerak.enums.CameraDeviceType
import dev.kashifmehmood.camerak.enums.CameraLens
import dev.kashifmehmood.camerak.enums.FlashMode
import dev.kashifmehmood.camerak.enums.TorchMode
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.state.CameraConfiguration
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The desktop preview reads the engine's `preview.frames` (desktop-preview-collector-leak, D1'):
 * null before a session's first frame and after stop, otherwise the producer's latest frame as an
 * opaque `TYPE_3BYTE_BGR` image.
 */
class DesktopPreviewSourceTest {
    private val desired = DesiredHardware(
        selection = CameraSelection.ByLens(CameraLens.BACK, CameraDeviceType.DEFAULT),
        flashMode = FlashMode.OFF,
        torchMode = TorchMode.OFF,
        zoomRatio = 1f,
        targetOrientation = null,
    )

    @Test
    fun framesAreNullBeforeTheFirstFrameAndAfterStop() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        rig.camera.holdOpen = true
        // A 4:3 configuration for the 4:3 camera, so the blit crops nothing (D4').
        val sut = rig.engine(CameraConfiguration { aspectRatio = AspectRatio.RATIO_4_3 })
        try {
            assertNull(sut.preview.frames.value, "a frame before any session")
            val start = async { sut.start(desired) }
            rig.settle()
            assertNull(sut.preview.frames.value, "a frame before the session's first")

            rig.camera.deliverFrame()
            rig.settle()
            assertEquals(CameraKResult.Success(Unit), start.await())
            val frame = assertNotNull(sut.preview.frames.value, "no frame after Open")
            assertEquals(BufferedImage.TYPE_3BYTE_BGR, frame.image.type)
            assertEquals(64 to 48, frame.image.width to frame.image.height)

            sut.stop()
            assertEquals(EngineState.Closed, sut.state.value)
            assertNull(sut.preview.frames.value, "the last frame outlived stop")
        } finally {
            sut.close()
        }
    }
}

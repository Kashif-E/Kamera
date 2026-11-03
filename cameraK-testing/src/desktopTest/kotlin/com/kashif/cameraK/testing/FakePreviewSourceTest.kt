@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.testing

import com.kashif.cameraK.utils.CameraKEngineApi
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class FakePreviewSourceTest {
    @Test
    fun desktopEmitsOneFixedFrame() {
        val frames = fakePreviewSource().frames

        val frame = assertNotNull(frames.value, "the fake preview has a frame from construction on")
        assertEquals(640, frame.image.width)
        assertEquals(480, frame.image.height)
        assertEquals(BufferedImage.TYPE_3BYTE_BGR, frame.image.type)
        assertEquals(0x202020, frame.image.getRGB(0, 0) and 0xFFFFFF, "left half")
        assertEquals(0xE0E0E0, frame.image.getRGB(639, 0) and 0xFFFFFF, "right half")
        assertEquals(0L, frame.timestampNanos)
        assertSame(frame, frames.value, "the frame never changes")
    }
}

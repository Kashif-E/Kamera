package com.kashif.cameraK.testing

import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.state.CameraConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The fake engine's frame source reads the session format and the open state from the engine. */
class FakeEngineFrameSourceTest {
    @Test
    fun fakeEngineExposesItsAnalysisFormatThroughItsFrameSource() {
        val fake = FakeCameraEngine()
        fake.configuration = CameraConfiguration { analysisFormat = FrameFormat.RGBA_8888 }

        assertEquals(FrameFormat.RGBA_8888, fake.fakeFrames.sessionFormat())
        assertFalse(fake.fakeFrames.isOpen(), "a fake that never started is not open")
    }

    @Test
    fun aFrameSourceOnItsOwnHasAnOpenYuvSession() {
        val source = FakeFrameSource()

        assertEquals(FrameFormat.YUV_420, source.sessionFormat())
        assertTrue(source.isOpen())
    }
}

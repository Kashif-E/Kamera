package com.kashif.cameraK.state

import com.kashif.cameraK.frames.FrameFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The five fields S1' appends to the 1.x configuration default to what 1.x already does: the
 * configured lens rather than a device id, the platform's app-owned folder, no analysis output until
 * a plugin needs frames, an analysis size that follows the preview, and YUV frames.
 */
class CameraConfigurationFieldsTest {
    @Test
    fun newFieldsDefaultToNullFalseAndYuv() {
        val config = CameraConfiguration()

        assertNull(config.cameraId, "cameraId")
        assertNull(config.outputDirectory, "outputDirectory")
        assertFalse(config.frameAnalysis, "frameAnalysis")
        assertNull(config.analysisResolution, "analysisResolution")
        assertEquals(FrameFormat.YUV_420, config.analysisFormat, "analysisFormat")
    }

    @Test
    fun newFieldsKeepWhatTheyAreGiven() {
        val config = CameraConfiguration(
            cameraId = "/dev/video2",
            outputDirectory = "/app/files/shots",
            frameAnalysis = true,
            analysisResolution = Resolution(640, 480),
            analysisFormat = FrameFormat.RGBA_8888,
        )

        assertEquals("/dev/video2", config.cameraId)
        assertEquals("/app/files/shots", config.outputDirectory)
        assertEquals(true, config.frameAnalysis)
        assertEquals(Resolution(640, 480), config.analysisResolution)
        assertEquals(FrameFormat.RGBA_8888, config.analysisFormat)
        assertEquals(config, config.copy(), "the fields take part in equality")
    }
}

package com.kashif.cameraK.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The video DSL keeps the data class's defaults (design §5, §6.1): FHD, no audio, unlimited, app-owned, "VID". */
class VideoConfigurationDslTest {
    @Test
    fun dslMatchesTheDataClassDefaults() {
        val config = VideoConfiguration {}

        assertEquals(VideoQuality.FHD, config.quality, "quality")
        assertEquals(false, config.enableAudio, "enableAudio")
        assertNull(config.maxDuration, "maxDuration")
        assertNull(config.outputDirectory, "outputDirectory")
        assertEquals("VID", config.filePrefix, "filePrefix")
        assertNull(config.maxFileSizeBytes, "maxFileSizeBytes")
    }
}

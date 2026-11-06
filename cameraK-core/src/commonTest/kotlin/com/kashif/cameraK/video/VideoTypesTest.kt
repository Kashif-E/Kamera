package com.kashif.cameraK.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class VideoTypesTest {

    @Test
    fun videoQuality_SD_hasDimensions640x480() {
        assertEquals(640, VideoQuality.SD.width)
        assertEquals(480, VideoQuality.SD.height)
    }

    @Test
    fun videoQuality_HD_hasDimensions1280x720() {
        assertEquals(1280, VideoQuality.HD.width)
        assertEquals(720, VideoQuality.HD.height)
    }

    @Test
    fun videoQuality_FHD_hasDimensions1920x1080() {
        assertEquals(1920, VideoQuality.FHD.width)
        assertEquals(1080, VideoQuality.FHD.height)
    }

    @Test
    fun videoQuality_UHD_hasDimensions3840x2160() {
        assertEquals(3840, VideoQuality.UHD.width)
        assertEquals(2160, VideoQuality.UHD.height)
    }

    @Test
    fun videoQuality_bitratesIncreaseWithQuality() {
        assertTrue(VideoQuality.SD.bitrateBps < VideoQuality.HD.bitrateBps)
        assertTrue(VideoQuality.HD.bitrateBps < VideoQuality.FHD.bitrateBps)
        assertTrue(VideoQuality.FHD.bitrateBps < VideoQuality.UHD.bitrateBps)
    }

    @Test
    fun videoQuality_entriesContainsAllValues() {
        val entries = VideoQuality.entries
        assertEquals(4, entries.size)
        assertTrue(entries.contains(VideoQuality.SD))
        assertTrue(entries.contains(VideoQuality.HD))
        assertTrue(entries.contains(VideoQuality.FHD))
        assertTrue(entries.contains(VideoQuality.UHD))
    }

    @Test
    fun videoConfiguration_defaultValues() {
        val config = VideoConfiguration()
        assertEquals(VideoQuality.FHD, config.quality)
        assertFalse(config.enableAudio)
        assertNull(config.maxDuration)
        assertEquals(null, config.outputDirectory)
        assertEquals("VID", config.filePrefix)
    }

    @Test
    fun videoConfiguration_customValues() {
        val config = VideoConfiguration {
            quality = VideoQuality.UHD
            enableAudio = false
            maxDuration = 60.seconds
            outputDirectory = "/tmp/videos"
            filePrefix = "TEST"
        }
        assertEquals(VideoQuality.UHD, config.quality)
        assertEquals(false, config.enableAudio)
        assertEquals(60.seconds, config.maxDuration)
        assertEquals("/tmp/videos", config.outputDirectory)
        assertEquals("TEST", config.filePrefix)
    }

    @Test
    fun videoConfiguration_copyPreservesUnchangedFields() {
        val original = VideoConfiguration {
            quality = VideoQuality.HD
            enableAudio = false
            maxDuration = 30.seconds
        }
        val copy = original.copy { enableAudio = true }
        assertEquals(VideoQuality.HD, copy.quality)
        assertTrue(copy.enableAudio)
        assertEquals(30.seconds, copy.maxDuration)
    }

    @Test
    fun videoConfiguration_equalityByValue() {
        val a = VideoConfiguration {
            quality = VideoQuality.FHD
            enableAudio = true
        }
        val b = VideoConfiguration {
            quality = VideoQuality.FHD
            enableAudio = true
        }
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun videoConfiguration_inequalityOnDifferentValues() {
        val a = VideoConfiguration { quality = VideoQuality.FHD }
        val b = VideoConfiguration { quality = VideoQuality.HD }
        assertNotEquals(a, b)
    }
}

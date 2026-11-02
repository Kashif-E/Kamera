package com.kashif.cameraK.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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
        assertTrue(config.enableAudio)
        assertEquals(0L, config.maxDurationMs)
        assertEquals(null, config.outputDirectory)
        assertEquals("VID", config.filePrefix)
    }

    @Test
    fun videoConfiguration_customValues() {
        val config = VideoConfiguration(
            quality = VideoQuality.UHD,
            enableAudio = false,
            maxDurationMs = 60_000L,
            outputDirectory = "/tmp/videos",
            filePrefix = "TEST",
        )
        assertEquals(VideoQuality.UHD, config.quality)
        assertEquals(false, config.enableAudio)
        assertEquals(60_000L, config.maxDurationMs)
        assertEquals("/tmp/videos", config.outputDirectory)
        assertEquals("TEST", config.filePrefix)
    }

    @Test
    fun videoConfiguration_copyPreservesUnchangedFields() {
        val original = VideoConfiguration(
            quality = VideoQuality.HD,
            enableAudio = false,
            maxDurationMs = 30_000L,
        )
        val copy = original.copy(enableAudio = true)
        assertEquals(VideoQuality.HD, copy.quality)
        assertTrue(copy.enableAudio)
        assertEquals(30_000L, copy.maxDurationMs)
    }

    @Test
    fun videoConfiguration_equalityByValue() {
        val a = VideoConfiguration(quality = VideoQuality.FHD, enableAudio = true)
        val b = VideoConfiguration(quality = VideoQuality.FHD, enableAudio = true)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun videoConfiguration_inequalityOnDifferentValues() {
        val a = VideoConfiguration(quality = VideoQuality.FHD)
        val b = VideoConfiguration(quality = VideoQuality.HD)
        assertNotEquals(a, b)
    }

    @Test
    fun videoCaptureResult_successHoldsFilePathAndDuration() {
        val result = VideoCaptureResult.Success(filePath = "/path/to/video.mp4", durationMs = 5000L)
        assertIs<VideoCaptureResult.Success>(result)
        assertEquals("/path/to/video.mp4", result.filePath)
        assertEquals(5000L, result.durationMs)
    }

    @Test
    fun videoCaptureResult_errorHoldsException() {
        val exception = RuntimeException("Recording failed")
        val result = VideoCaptureResult.Error(exception)
        assertIs<VideoCaptureResult.Error>(result)
        assertEquals("Recording failed", result.exception.message)
    }

    @Test
    fun videoCaptureResult_successIsNotError() {
        val result: VideoCaptureResult = VideoCaptureResult.Success("/path", 1000L)
        assertIs<VideoCaptureResult.Success>(result)
    }

    @Test
    fun videoCaptureResult_errorIsNotSuccess() {
        val result: VideoCaptureResult = VideoCaptureResult.Error(Exception("fail"))
        assertIs<VideoCaptureResult.Error>(result)
    }

    @Test
    fun videoCaptureResult_successEquality() {
        val a = VideoCaptureResult.Success("/path", 1000L)
        val b = VideoCaptureResult.Success("/path", 1000L)
        assertEquals(a, b)
    }
}

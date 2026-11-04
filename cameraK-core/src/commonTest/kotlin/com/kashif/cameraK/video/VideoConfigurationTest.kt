package com.kashif.cameraK.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** The 2.0 recording configuration: the microphone is opt-in, limits are nullable, the prefix is a safe file name. */
class VideoConfigurationTest {
    @Test
    fun enableAudioDefaultsFalse() {
        assertFalse(VideoConfiguration().enableAudio, "the microphone is on by default")
    }

    @Test
    fun nullLimitsAreUnlimited() {
        val unlimited = VideoConfiguration()
        assertNull(unlimited.maxDuration, "a default recording has a duration limit")
        assertNull(unlimited.maxFileSizeBytes, "a default recording has a size limit")

        val bounded = VideoConfiguration(maxDuration = 5.minutes, maxFileSizeBytes = 1_000_000L)
        assertEquals(5.minutes, bounded.maxDuration)
        assertEquals(1_000_000L, bounded.maxFileSizeBytes)
    }

    @Test
    fun negativeLimitsAreRejected() {
        assertFailsWith<IllegalArgumentException>("a negative duration limit was accepted") {
            VideoConfiguration(maxDuration = (-1).milliseconds)
        }
        assertFailsWith<IllegalArgumentException>("a negative size limit was accepted") {
            VideoConfiguration(maxFileSizeBytes = -1L)
        }
    }

    @Test
    fun filePrefixIsValidated() {
        listOf("VID", "a", "clip-01", "My_Video.v2", "x".repeat(64)).forEach { prefix ->
            assertEquals(prefix, VideoConfiguration(filePrefix = prefix).filePrefix, "a valid prefix was refused")
        }
        val invalid = listOf("", ".", "..", "x".repeat(65), "a/b", "..\\b", "a b", "vid:1", "clip\u0000", "é")
        invalid.forEach { prefix ->
            assertFailsWith<IllegalArgumentException>("the prefix '$prefix' was accepted") {
                VideoConfiguration(filePrefix = prefix)
            }
        }
    }
}

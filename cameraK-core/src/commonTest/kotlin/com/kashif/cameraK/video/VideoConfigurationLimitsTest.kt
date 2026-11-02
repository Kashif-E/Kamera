package com.kashif.cameraK.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A recording limit is either 0 (unlimited) or a positive bound. A negative one is a caller bug, and
 * reading it as "unlimited" would record without the bound the caller believed they had set.
 */
class VideoConfigurationLimitsTest {
    @Test
    fun negativeFileSizeLimit_isRejected() {
        assertFailsWith<IllegalArgumentException>("a negative file-size limit was accepted") {
            VideoConfiguration(maxFileSizeBytes = -1L)
        }
    }

    @Test
    fun negativeDurationLimit_isRejected() {
        assertFailsWith<IllegalArgumentException>("a negative duration limit was accepted") {
            VideoConfiguration(maxDurationMs = -1L)
        }
    }

    @Test
    fun zeroAndPositiveLimits_areAccepted() {
        val unlimited = VideoConfiguration(maxDurationMs = 0L, maxFileSizeBytes = 0L)
        val bounded = VideoConfiguration(maxDurationMs = 1L, maxFileSizeBytes = 1L)

        assertEquals(0L, unlimited.maxFileSizeBytes)
        assertEquals(1L, bounded.maxDurationMs)
    }
}

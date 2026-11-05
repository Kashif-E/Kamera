package com.kashif.cameraK.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A recording limit is either null (unlimited) or a bound that is not negative. A negative one is a caller bug, and
 * reading it as "unlimited" would record without the bound the caller believed they had set.
 */
class VideoConfigurationLimitsTest {
    @Test
    fun negativeFileSizeLimit_isRejected() {
        assertFailsWith<IllegalArgumentException>("a negative file-size limit was accepted") {
            VideoConfiguration { maxFileSizeBytes = -1L }
        }
    }

    @Test
    fun negativeDurationLimit_isRejected() {
        assertFailsWith<IllegalArgumentException>("a negative duration limit was accepted") {
            VideoConfiguration { maxDuration = (-1).milliseconds }
        }
    }

    @Test
    fun zeroAndPositiveLimits_areAccepted() {
        val unlimited = VideoConfiguration {
            maxDuration = Duration.ZERO
            maxFileSizeBytes = 0L
        }
        val bounded = VideoConfiguration {
            maxDuration = 1.milliseconds
            maxFileSizeBytes = 1L
        }

        assertEquals(0L, unlimited.maxFileSizeBytes)
        assertEquals(1.milliseconds, bounded.maxDuration)
    }
}

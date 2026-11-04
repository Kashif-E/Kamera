package com.kashif.cameraK.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The common recording clock, which D3''s desktop engine adopts in place of its own copy. */
class RecordingClockTest {
    private val ms = 1_000_000L

    @Test
    fun pauseAwareElapsed() {
        val clock = RecordingClock(startNanos = 1_000 * ms)

        assertEquals(0L, clock.recordedMillis(1_000 * ms), "at the start")
        assertEquals(2_000L, clock.recordedMillis(3_000 * ms), "two seconds in")

        clock.pause(3_000 * ms)
        assertEquals(2_000L, clock.recordedMillis(4_500 * ms), "the elapsed time moved while paused")
        clock.pause(4_000 * ms)
        assertEquals(2_000L, clock.recordedMillis(5_000 * ms), "a second pause moved the pause start")

        clock.resume(6_000 * ms)
        assertEquals(2_500L, clock.recordedMillis(6_500 * ms), "after the resume")
        clock.resume(7_000 * ms)
        assertEquals(3_000L, clock.recordedMillis(7_000 * ms), "a resume while recording took time out")
    }

    @Test
    fun timestampsSkipPausedFramesAndStrictlyIncrease() {
        val clock = RecordingClock(startNanos = 1_000 * ms)

        assertNull(clock.timestampMicros(500 * ms), "a frame from before the start")
        assertNull(clock.timestampMicros(1_000 * ms), "a frame at the start instant")
        assertEquals(10_000L, clock.timestampMicros(1_010 * ms))
        assertNull(clock.timestampMicros(1_010 * ms), "the same capture time twice")
        assertNull(clock.timestampMicros(1_005 * ms), "a frame older than the previous one")
        assertEquals(20_000L, clock.timestampMicros(1_020 * ms))

        clock.pause(1_030 * ms)
        assertNull(clock.timestampMicros(1_040 * ms), "a frame captured while paused")
        clock.resume(2_030 * ms)
        assertNull(clock.timestampMicros(2_030 * ms), "a frame at the resume instant")
        assertNull(clock.timestampMicros(1_500 * ms), "a frame captured before the resume")
        assertEquals(40_000L, clock.timestampMicros(2_040 * ms), "the pause left a gap in the timeline")
    }
}

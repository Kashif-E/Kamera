package com.kashif.ocrPlugin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The live OCR rate gate keeps working when its clock steps backwards, as a wall clock does when the
 * system corrects the time: it must not wait for the old time to come round again.
 */
class FrameRateGateTest {
    @Test
    fun aTimestampBeforeTheLastPass_doesNotStallTheGate() {
        val gate = FrameRateGate(maxFps = 2)
        assertTrue(gate.tryPass(10 * SECOND), "the first frame")

        assertTrue(gate.tryPass(5 * SECOND), "the first frame after the clock stepped back five seconds")
        assertFalse(gate.tryPass(5 * SECOND + SECOND / 4), "a frame a quarter of a second after that one")
        assertTrue(gate.tryPass(5 * SECOND + SECOND / 2), "a frame half a second after that one")
    }

    private companion object {
        const val SECOND = 1_000_000_000L
    }
}

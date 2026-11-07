package com.kashif.cameraK.controller

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CameraK's iOS exposure index (w13 Z-focus-ios): one step is 1/3 EV, and a camera's indexes are its
 * `exposureTargetBias` range intersected with ±2 EV, which is -6 to 6 on every current iPhone. A range
 * of one index or none is no compensation.
 */
class ExposureBiasIndexTest {
    @Test
    fun aWideBiasRangeIsCutToTwoEvEachWay() {
        assertEquals(-6..6, exposureIndexRange(-8f, 8f), "an iPhone's -8 to 8 EV")
        assertEquals(-6..6, exposureIndexRange(-2f, 2f), "exactly ±2 EV keeps both ends")
        assertEquals(-6..3, exposureIndexRange(-8f, 1f), "one end inside ±2 EV")
    }

    @Test
    fun aNarrowerDeviceRangeKeepsTheIndexesInsideIt() {
        assertEquals(-4..4, exposureIndexRange(-1.5f, 1.5f), "-1.5 to 1.5 EV holds -4/3 to 4/3")
        assertEquals(-1..1, exposureIndexRange(-0.4f, 0.4f), "-0.4 to 0.4 EV holds -1/3 to 1/3")
    }

    @Test
    fun aRangeOfOneIndexOrNoneIsNoCompensation() {
        assertNull(exposureIndexRange(0f, 0f), "a fixed bias")
        assertNull(exposureIndexRange(-0.2f, 0.2f), "a range holding only index 0")
        assertNull(exposureIndexRange(1f, -1f), "an inverted range")
    }

    @Test
    fun everyIndexsBiasIsInsideTheDevicesBounds() {
        // A bound just short of a third of an EV: the index there would set a bias past it, and
        // AVFoundation raises an exception for that which Kotlin cannot catch.
        assertEquals(-2..2, exposureIndexRange(-0.9997f, 0.9997f), "a bound just short of 1 EV")
        assertEquals(-4..4, exposureIndexRange(-1.6666f, 1.6666f), "a bound just short of 5/3 EV")
        val bounds =
            listOf(0.9997f, 1f, 1f / 3, 0.3333333f, 2f / 3, 0.6666666f, 1.3333333f, 1.6666666f, 1.9999f, 2f, 8f)
        for (max in bounds) {
            for (min in bounds.map { -it }) {
                val range = exposureIndexRange(min, max) ?: continue
                for (index in range) {
                    val bias = exposureBiasOf(index)
                    assertTrue(bias in min..max, "index $index's bias $bias is outside $min..$max")
                }
            }
        }
    }

    @Test
    fun theBiasSetIsClampedIntoTheDevicesBounds() {
        assertEquals(0.9997f, exposureBiasOf(3, -0.9997f, 0.9997f), "index 3 against a bound just short of 1 EV")
        assertEquals(-0.9997f, exposureBiasOf(-3, -0.9997f, 0.9997f), "index -3 against a bound just short of -1 EV")
        assertEquals(0.5f, exposureBiasOf(0, 0.5f, 1.5f), "index 0 on a device whose bias excludes 0")
        assertClose(2f / 3, exposureBiasOf(2, -8f, 8f), "an index inside the bounds is its own bias")
    }

    @Test
    fun anIndexIsAThirdOfAnEv() {
        assertClose(1f, exposureBiasOf(3), "index 3")
        assertClose(-2f, exposureBiasOf(-6), "index -6")
        assertClose(0f, exposureBiasOf(0), "index 0")
        assertClose(1f / 3, EXPOSURE_STEP_EV, "the step")
    }

    @Test
    fun aBiasRoundsToTheNearestIndex() {
        assertEquals(3, exposureIndexOf(1f))
        assertEquals(2, exposureIndexOf(0.6f), "0.6 EV is nearer 2/3 than 1/3")
        assertEquals(-2, exposureIndexOf(-0.6f), "-0.6 EV is nearer -2/3 than -1/3")
        assertEquals(1, exposureIndexOf(0.34f))
        assertEquals(-1, exposureIndexOf(-0.32f))
        assertEquals(6, exposureIndexOf(2f))
        for (index in -6..6) assertEquals(index, exposureIndexOf(exposureBiasOf(index)), "index $index round trip")
    }

    private fun assertClose(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) < 1e-6f, "$what: expected $expected, was $actual")
}

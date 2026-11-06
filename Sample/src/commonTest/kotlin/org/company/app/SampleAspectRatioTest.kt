package org.company.app

import com.kashif.cameraK.enums.AspectRatio
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The aspect-ratio button cycles the ratios CameraK recommends. RATIO_9_16 is deprecated as the
 * same as RATIO_16_9, so the Sample, which demonstrates CameraK, does not offer it.
 */
class SampleAspectRatioTest {
    @Test
    fun theCycleVisitsEachRecommendedRatioOnce() {
        val visited = generateSequence(AspectRatio.RATIO_4_3) { it.nextInSample() }.take(4).toList()
        assertEquals(
            listOf(AspectRatio.RATIO_4_3, AspectRatio.RATIO_16_9, AspectRatio.RATIO_1_1, AspectRatio.RATIO_4_3),
            visited,
        )
    }

    // A RATIO_9_16 restored from an older saved state moves on to the next ratio, not back to itself.
    @Suppress("DEPRECATION")
    @Test
    fun aRestoredDeprecatedRatioMovesOn() {
        assertEquals(AspectRatio.RATIO_1_1, AspectRatio.RATIO_9_16.nextInSample())
    }
}

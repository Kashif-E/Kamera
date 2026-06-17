package com.kashif.cameraK.enums

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AspectRatioTest {

    @Test
    fun portrait_ratiosAreHeightDominant() {
        // In portrait the long edge is vertical, so width:height < 1 for non-square ratios.
        assertEquals(3f / 4f, AspectRatio.RATIO_4_3.previewAspectRatio(DeviceOrientation.PORTRAIT))
        assertEquals(9f / 16f, AspectRatio.RATIO_16_9.previewAspectRatio(DeviceOrientation.PORTRAIT))
        assertEquals(9f / 16f, AspectRatio.RATIO_9_16.previewAspectRatio(DeviceOrientation.PORTRAIT))
        assertEquals(1f, AspectRatio.RATIO_1_1.previewAspectRatio(DeviceOrientation.PORTRAIT))
    }

    @Test
    fun landscape_ratiosAreWidthDominant() {
        assertEquals(4f / 3f, AspectRatio.RATIO_4_3.previewAspectRatio(DeviceOrientation.LANDSCAPE_LEFT))
        assertEquals(16f / 9f, AspectRatio.RATIO_16_9.previewAspectRatio(DeviceOrientation.LANDSCAPE_RIGHT))
        assertEquals(1f, AspectRatio.RATIO_1_1.previewAspectRatio(DeviceOrientation.LANDSCAPE_LEFT))
    }

    @Test
    fun portraitAndLandscapeAreReciprocal() {
        AspectRatio.entries.forEach { ratio ->
            val portrait = ratio.previewAspectRatio(DeviceOrientation.PORTRAIT)
            val landscape = ratio.previewAspectRatio(DeviceOrientation.LANDSCAPE_LEFT)
            assertTrue(
                kotlin.math.abs(portrait * landscape - 1f) < 0.0001f,
                "$ratio should be reciprocal across orientations",
            )
        }
    }
}

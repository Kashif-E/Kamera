package com.kashif.cameraK.enums

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * RATIO_9_16 means RATIO_16_9 on every platform, so it is deprecated at WARNING with the rewrite to
 * RATIO_16_9: a 1.x call site still compiles and is told what it already gets.
 */
class AspectRatioDeprecationTest {
    @Test
    fun ratio9By16IsDeprecatedAtWarningInFavourOf16By9() {
        val deprecation = assertNotNull(
            AspectRatio::class.java.getField("RATIO_9_16").getAnnotation(Deprecated::class.java),
            "RATIO_9_16 is not deprecated",
        )

        assertEquals(DeprecationLevel.WARNING, deprecation.level)
        assertEquals("AspectRatio.RATIO_16_9", deprecation.replaceWith.expression)
    }
}

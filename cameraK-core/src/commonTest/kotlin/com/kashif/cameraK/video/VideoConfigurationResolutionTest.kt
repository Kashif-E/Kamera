package com.kashif.cameraK.video

import kotlin.test.Test
import kotlin.test.assertEquals

/** `VideoConfiguration()` resolves to the DSL, not to the ERROR-level named-argument hint (design §6.1). */
class VideoConfigurationResolutionTest {
    @Test
    fun zeroArgumentCallResolvesToTheDsl() {
        assertEquals(VideoConfiguration {}, VideoConfiguration())
    }
}

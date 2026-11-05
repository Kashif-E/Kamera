package com.kashif.cameraK.state

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `CameraConfiguration()` resolves to the DSL, not to the ERROR-level named-argument hint (design §6.1
 * "Zero-argument calls resolve to the DSL"): this file does not compile if the call is ambiguous, and
 * throws if it reaches the hint. The library writes its defaults this way.
 */
class CameraConfigurationResolutionTest {
    @Test
    fun zeroArgumentCallResolvesToTheDsl() {
        assertEquals(CameraConfiguration {}, CameraConfiguration())
    }
}

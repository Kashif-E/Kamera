package com.kashif.cameraK.testing

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * camerak-core's own tests depend on camerak-testing, which depends on camerak-core. This compiles
 * only if the build resolves that back edge, and it runs on every target core tests.
 */
class TestingBackEdgeTest {
    @Test
    fun commonTestSeesCameraKTesting() {
        assertEquals(emptyList(), FakeCameraEngine().calls)
    }
}

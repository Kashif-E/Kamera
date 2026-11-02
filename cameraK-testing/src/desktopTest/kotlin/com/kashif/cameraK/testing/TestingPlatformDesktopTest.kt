package com.kashif.cameraK.testing

import kotlin.test.Test
import kotlin.test.assertEquals

/** The desktop actual of [testingPlatform] links and is the one this target uses. */
class TestingPlatformDesktopTest {
    @Test
    fun isDesktop() {
        assertEquals("desktop", testingPlatform)
    }
}

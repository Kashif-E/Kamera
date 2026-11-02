package com.kashif.cameraK.testing

import kotlin.test.Test
import kotlin.test.assertEquals

/** The apple actual of [testingPlatform] links and is the one this target uses. */
class TestingPlatformAppleTest {
    @Test
    fun isApple() {
        assertEquals("apple", testingPlatform)
    }
}

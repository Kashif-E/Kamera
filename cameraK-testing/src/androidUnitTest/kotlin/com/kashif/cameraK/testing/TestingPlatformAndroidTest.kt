package com.kashif.cameraK.testing

import kotlin.test.Test
import kotlin.test.assertEquals

/** The android actual of [testingPlatform] links and is the one this target uses. */
class TestingPlatformAndroidTest {
    @Test
    fun isAndroid() {
        assertEquals("android", testingPlatform)
    }
}

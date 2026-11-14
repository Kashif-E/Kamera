package org.company.app

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * On desktop the Sample opens the camera before its permission is answered: macOS asks when the
 * camera opens, and CameraK's request cannot show that prompt. Linux and Windows read Granted anyway.
 */
class CameraAsksWhenOpenedTest {
    @Test
    fun desktopOpensTheCameraToAsk() {
        assertTrue(cameraAsksWhenOpened)
    }
}

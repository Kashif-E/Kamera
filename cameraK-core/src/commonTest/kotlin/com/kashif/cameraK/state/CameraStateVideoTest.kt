package com.kashif.cameraK.state

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraStateVideoTest {
    // ═══════════════════════════════════════════════════════════════
    // CameraConfiguration Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun cameraConfiguration_mirrorFrontCameraDefaultsToFalse() {
        assertFalse(CameraConfiguration().mirrorFrontCamera)
    }

    @Test
    fun cameraConfiguration_mirrorFrontCameraPropagates() {
        assertTrue(CameraConfiguration { mirrorFrontCamera = true }.mirrorFrontCamera)
        assertTrue(CameraConfiguration().copy { mirrorFrontCamera = true }.mirrorFrontCamera)
    }
}

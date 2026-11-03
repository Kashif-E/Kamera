package com.kashif.cameraK.screenshots

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.kashif.cameraK.compose.CameraKScreen
import com.kashif.cameraK.compose.DefaultErrorScreen
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.state.CameraKState
import org.junit.Rule
import org.junit.Test

/**
 * Snapshots of the camera-free states of [CameraKScreen].
 *
 * The Ready state is not covered: it renders CameraPreviewView, which needs a CameraX
 * PreviewView bound to a live camera, and layoutlib has no camera.
 */
class CameraKScreenPaparazziTest {
    // Pixel-exact comparison; see PixelExactSnapshotVerifier for why maxPercentDifference = 0.0 alone
    // is not. Goldens are recorded and verified on macOS (CI runs macos-15); layoutlib's native
    // runtime differs per OS.
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = DeviceConfig.PIXEL_5,
            maxPercentDifference = 0.0,
            snapshotHandler = PixelExactSnapshotVerifier.forCurrentMode(),
        )

    @Test
    fun initializing_showsDefaultLoadingScreen() {
        paparazzi.snapshot {
            CameraKScreen(cameraState = CameraKState.Starting) {}
        }
    }

    @Test
    fun error_retryable_showsRetryHint() {
        paparazzi.snapshot {
            CameraKScreen(cameraState = retryableError) {}
        }
    }

    @Test
    fun error_notRetryable_hidesRetryHint() {
        paparazzi.snapshot {
            DefaultErrorScreen(CameraKState.Error(CameraKError.CameraDisabled()))
        }
    }

    private val retryableError = CameraKState.Error(CameraKError.CameraInUse(IllegalStateException("camera in use")))
}

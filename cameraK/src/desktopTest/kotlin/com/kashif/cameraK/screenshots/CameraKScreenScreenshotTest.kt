package com.kashif.cameraK.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kashif.cameraK.compose.CameraKScreen
import com.kashif.cameraK.compose.CameraPreviewScopeImpl
import com.kashif.cameraK.compose.DefaultErrorScreen
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.state.CameraKState
import kotlin.test.Test

/**
 * Desktop renders of the cameraK composables that need no camera.
 *
 * CameraKScreen's Ready state is not covered: it renders CameraPreviewView, which on desktop
 * draws frames grabbed from a webcam by a running CameraController.
 */
class CameraKScreenScreenshotTest {
    private val error = CameraKState.Error(CameraKError.CameraInUse(IllegalStateException("camera in use")))

    @Test
    fun initializing_showsDefaultLoadingScreen() = Goldens.assertMatches("camerakscreen_initializing", 320, 240) {
        CameraKScreen(modifier = Modifier.fillMaxSize(), cameraState = CameraKState.Starting) {}
    }

    @Test
    fun error_retryable_showsRetryHint() = Goldens.assertMatches("camerakscreen_error_retryable", 320, 240) {
        CameraKScreen(modifier = Modifier.fillMaxSize(), cameraState = error) {}
    }

    @Test
    fun error_notRetryable_hidesRetryHint() = Goldens.assertMatches("defaulterrorscreen_not_retryable", 320, 240) {
        DefaultErrorScreen(CameraKState.Error(CameraKError.CameraDisabled()))
    }

    @Test
    fun keepUpright_portrait_leavesOverlayUnrotated() = Goldens.assertMatches("keepupright_portrait", 160, 160) {
        UprightMarker(DeviceOrientation.PORTRAIT)
    }

    @Test
    fun keepUpright_landscapeLeft_rotatesOverlayBack() = Goldens.assertMatches("keepupright_landscape_left", 160, 160) {
        UprightMarker(DeviceOrientation.LANDSCAPE_LEFT)
    }

    /** A bar with a red cap on its left end, so a rotation shows up as the cap moving. */
    @androidx.compose.runtime.Composable
    private fun UprightMarker(orientation: DeviceOrientation) {
        Box(Modifier.fillMaxSize().background(Color.White)) {
            with(CameraPreviewScopeImpl(this, orientation)) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .keepUpright()
                        .size(width = 120.dp, height = 40.dp)
                        .background(Color.Black),
                ) {
                    Box(Modifier.fillMaxHeight().width(24.dp).background(Color.Red))
                }
            }
        }
    }
}

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
import com.kashif.cameraK.compose.CameraPreviewScope
import com.kashif.cameraK.compose.CameraPreviewScopeImpl
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.fakeCameraKStateHolder
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * Desktop renders of the cameraK composables that need no camera.
 *
 * CameraKScreen(holder) runs over camerak-testing's fake engine: in Ready and Interrupted the
 * preview draws the fake's fixed 640 x 480 frame (dark left half, light right half).
 */
class CameraKScreenScreenshotTest {
    /** Starting shows the loading slot, here a green square, over the caller's background. */
    @Test
    fun holder_starting_showsTheLoadingSlot() = runTest {
        val fake = FakeCameraEngine().apply { autoOpen = false }
        val holder = fakeCameraKStateHolder(fake = fake)
        holder.start()
        runCurrent()
        assertIs<CameraKState.Starting>(holder.cameraState.value)
        Goldens.assertMatches("camerakscreen_holder_starting", 320, 320) {
            CameraKScreen(holder, Modifier.fillMaxSize().background(Color.Blue), loading = { LoadingMarker() }) {
                ContentMarker()
            }
        }
    }

    @Test
    fun holder_ready_showsTheFixedFrameWithContent() = runTest {
        val holder = fakeCameraKStateHolder()
        holder.start()
        runCurrent()
        assertIs<CameraKState.Ready>(holder.cameraState.value)
        Goldens.assertMatches("camerakscreen_holder_ready", 320, 320, until = ::showsTheFixedFrame) {
            CameraKScreen(holder, Modifier.fillMaxSize().background(Color.Blue)) { ContentMarker() }
        }
    }

    @Test
    fun holder_interrupted_keepsThePreviewAndContent() = runTest {
        val fake = FakeCameraEngine()
        val holder = fakeCameraKStateHolder(fake = fake)
        holder.start()
        runCurrent()
        fake.interrupt(InterruptionReason.IN_USE_BY_ANOTHER_CLIENT)
        runCurrent()
        assertIs<CameraKState.Interrupted>(holder.cameraState.value)
        Goldens.assertMatches("camerakscreen_holder_interrupted", 320, 320, until = ::showsTheFixedFrame) {
            CameraKScreen(holder, Modifier.fillMaxSize().background(Color.Blue)) { ContentMarker() }
        }
    }

    /** The default error slot draws no library text or colours: only the caller's background shows. */
    @Test
    fun holder_error_defaultSlotDrawsNothing() = runTest {
        val fake = FakeCameraEngine().apply { autoOpen = false }
        val holder = fakeCameraKStateHolder(fake = fake)
        holder.start()
        runCurrent()
        fake.failOpen(CameraKError.CameraDisabled())
        runCurrent()
        assertIs<CameraKState.Error>(holder.cameraState.value)
        Goldens.assertMatches("camerakscreen_holder_error", 320, 320) {
            CameraKScreen(holder, Modifier.fillMaxSize().background(Color.Blue)) { ContentMarker() }
        }
    }

    // In a square scene the 4:3 frame is letterboxed: 320 x 240 between bars 40 px high.
    private fun showsTheFixedFrame(image: BufferedImage): Boolean =
        image.getRGB(80, 160) and 0xFFFFFF == 0x202020 && image.getRGB(240, 160) and 0xFFFFFF == 0xE0E0E0

    /** A green square in the screen's centre: the loading slot. */
    @androidx.compose.runtime.Composable
    private fun LoadingMarker() {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(48.dp).background(Color.Green))
        }
    }

    /** A red square in the content's top start corner: content shares the frame's box, not the screen's. */
    @androidx.compose.runtime.Composable
    private fun CameraPreviewScope.ContentMarker() {
        Box(Modifier.align(Alignment.TopStart).size(24.dp).background(Color.Red))
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

package com.kashif.cameraK.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.kashif.cameraK.compose.CameraKScreen
import com.kashif.cameraK.compose.CameraPreviewScope
import com.kashif.cameraK.compose.DefaultErrorScreen
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.fakeCameraKStateHolder
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Snapshots of the camera-free states of [CameraKScreen].
 *
 * The 1.x CameraKScreen(cameraState)'s Ready state is not covered: it renders CameraPreviewView,
 * which needs a CameraX PreviewView bound to a live camera, and layoutlib has no camera.
 * CameraKScreen(holder) runs over camerak-testing's fake engine. layoutlib cannot attach a CameraX
 * PreviewView to a window, so Ready and Interrupted render in inspection mode, as an Android Studio
 * preview does: the preview draws no PreviewView and the content shows over the empty preview box.
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

    @Test
    fun holder_ready_showsThePreviewWithContent() = runTest {
        val holder = fakeCameraKStateHolder()
        holder.start()
        runCurrent()
        assertTrue("state is ${holder.cameraState.value}", holder.cameraState.value is CameraKState.Ready)
        paparazzi.snapshot {
            InspectionMode { CameraKScreen(holder, Modifier.fillMaxSize().background(Color.Blue)) { ContentMarker() } }
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
        assertTrue("state is ${holder.cameraState.value}", holder.cameraState.value is CameraKState.Interrupted)
        paparazzi.snapshot {
            InspectionMode { CameraKScreen(holder, Modifier.fillMaxSize().background(Color.Blue)) { ContentMarker() } }
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
        assertTrue("state is ${holder.cameraState.value}", holder.cameraState.value is CameraKState.Error)
        paparazzi.snapshot {
            CameraKScreen(holder, Modifier.fillMaxSize().background(Color.Blue)) { ContentMarker() }
        }
    }

    @Composable
    private fun InspectionMode(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalInspectionMode provides true, content = content)
    }

    @Composable
    private fun CameraPreviewScope.ContentMarker() {
        Box(Modifier.align(Alignment.TopStart).size(24.dp).background(Color.Red))
    }

    private val retryableError = CameraKState.Error(CameraKError.CameraInUse(IllegalStateException("camera in use")))
}

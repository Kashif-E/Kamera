package com.kashif.cameraK.compose

import android.content.res.Configuration
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.previewAspectRatio

@Composable
actual fun CameraPreviewView(
    controller: CameraController,
    modifier: Modifier,
    deviceOrientation: DeviceOrientation,
    overlay: @Composable (CameraPreviewScope.() -> Unit)?,
) {
    val context = LocalContext.current
    // FIT_CENTER shows the whole frame (letterboxed) instead of cropping it to fill the view,
    // so the preview matches the captured field of view.
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }

    DisposableEffect(controller, previewView) {
        controller.bindCamera(previewView) {}
        onDispose {}
    }

    // Size the preview to the configured aspect ratio so it matches the capture. Drive portrait vs
    // landscape from the actual screen orientation (recomposes on rotation) rather than the caller's
    // deviceOrientation, which defaults to PORTRAIT and would otherwise mis-size a landscape preview.
    // Sizing the box to the ratio also makes the UseCaseGroup's ViewPort (derived from this view) the
    // requested ratio, so the capture is no longer cropped to the full screen.
    val screenOrientation = if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        DeviceOrientation.LANDSCAPE_LEFT
    } else {
        DeviceOrientation.PORTRAIT
    }
    val ratio = controller.getAspectRatio().previewAspectRatio(screenOrientation)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Preview and overlay share the same aspect-ratio box so overlay coordinates (focus
        // reticles, bounding boxes) align with the letterboxed preview frame, not the outer bounds.
        Box(modifier = Modifier.aspectRatio(ratio)) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.matchParentSize(),
            )
            if (overlay != null) {
                val scope = CameraPreviewScopeImpl(this, deviceOrientation)
                scope.overlay()
            }
        }
    }
}

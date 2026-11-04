@file:OptIn(InternalCameraKApi::class, CameraKEngineApi::class)

package com.kashif.cameraK.compose

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.previewAspectRatio
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.InternalCameraKApi

@Composable
actual fun CameraPreviewView(
    controller: CameraController,
    modifier: Modifier,
    deviceOrientation: DeviceOrientation,
    overlay: @Composable (CameraPreviewScope.() -> Unit)?,
) {
    // Size the preview to the configured aspect ratio so it matches the capture. Drive portrait vs
    // landscape from the actual screen orientation (recomposes on rotation) rather than the caller's
    // deviceOrientation, which defaults to PORTRAIT and would otherwise mis-size a landscape preview.
    val screenOrientation = if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        DeviceOrientation.LANDSCAPE_LEFT
    } else {
        DeviceOrientation.PORTRAIT
    }
    val ratio = controller.getAspectRatio().previewAspectRatio(screenOrientation)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Preview and overlay share the same aspect-ratio box so overlay coordinates (focus
        // reticles, bounding boxes) align with the letterboxed preview frame, not the outer bounds.
        ViewfinderPreview(controller.previewSource, deviceOrientation, Modifier.aspectRatio(ratio), overlay)
    }
}

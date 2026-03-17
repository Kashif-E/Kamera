package com.kashif.cameraK.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.enums.DeviceOrientation

/**
 * Stateless camera preview composable.
 * Displays the camera feed from a [CameraController] with optional orientation-aware overlay.
 *
 * @param controller The initialized camera controller.
 * @param modifier Modifier to be applied to the preview.
 * @param deviceOrientation Current device orientation for overlay rotation.
 * @param overlay Optional overlay content that receives [CameraPreviewScope] with [keepUpright] modifier.
 */
@Composable
expect fun CameraPreviewView(
    controller: CameraController,
    modifier: Modifier = Modifier,
    deviceOrientation: DeviceOrientation = DeviceOrientation.PORTRAIT,
    overlay: @Composable (CameraPreviewScope.() -> Unit)? = null,
)

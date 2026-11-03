package com.kashif.cameraK.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.utils.InternalCameraKApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.UIKit.UIView

/**
 * The preview hosts a layer bound to the controller's session in a view of its own. It never starts
 * the camera, and composing it twice gives two layers on one session, not one reparented view
 * controller.
 */
@Composable
actual fun CameraPreviewView(
    controller: CameraController,
    modifier: Modifier,
    deviceOrientation: DeviceOrientation,
    overlay: @Composable (CameraPreviewScope.() -> Unit)?,
) {
    key(controller) {
        Box(modifier = modifier) {
            UIKitView(
                factory = { PreviewHostView(controller) },
                modifier = Modifier.fillMaxSize(),
                update = {},
                onRelease = { it.detach() },
            )
            if (overlay != null) CameraPreviewOverlay(deviceOrientation, overlay)
        }
    }
}

/**
 * Hosts the controller's preview layer and keeps it the size of the view. Each layout turns the
 * preview to the interface's orientation, which a rotation changes.
 */
@OptIn(ExperimentalForeignApi::class, InternalCameraKApi::class)
private class PreviewHostView(private val controller: CameraController) : UIView(frame = CGRectZero.readValue()) {
    private val previewLayer = controller.createPreviewLayer().also { layer.addSublayer(it) }

    override fun layoutSubviews() {
        super.layoutSubviews()
        previewLayer.setFrame(bounds)
        controller.previewLayoutChanged(window?.windowScene?.interfaceOrientation)
    }

    fun detach() {
        previewLayer.removeFromSuperlayer()
    }
}

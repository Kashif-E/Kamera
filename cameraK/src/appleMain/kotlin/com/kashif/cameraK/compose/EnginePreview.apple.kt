@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.CoreGraphics.CGRectZero
import platform.UIKit.UIView

/**
 * Hosts a preview layer of the engine's session in a view of its own. It never starts the camera,
 * and composing it twice gives two layers on one session.
 */
@Composable
internal actual fun EnginePreview(
    source: PlatformPreviewSource,
    deviceOrientation: DeviceOrientation,
    modifier: Modifier,
    overlay: (@Composable CameraPreviewScope.() -> Unit)?,
) {
    key(source) {
        Box(modifier = modifier) {
            UIKitView(
                factory = { PreviewLayerHostView(source, source.createPreviewLayer()) },
                modifier = Modifier.fillMaxSize(),
                update = {},
                onRelease = { it.detach() },
            )
            if (overlay != null) CameraPreviewOverlay(deviceOrientation, overlay)
        }
    }
}

/**
 * Hosts [previewLayer] and lays it out on each layout pass through [source]: a centred box of the
 * configured aspect ratio, turned by the rotation coordinator from iOS 17, otherwise to the
 * interface's orientation, which a rotation changes.
 */
@OptIn(ExperimentalForeignApi::class)
private class PreviewLayerHostView(
    private val source: PlatformPreviewSource,
    private val previewLayer: AVCaptureVideoPreviewLayer,
) : UIView(frame = CGRectZero.readValue()) {
    init {
        layer.addSublayer(previewLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        source.layOutPreviewLayer(previewLayer, bounds, window?.windowScene?.interfaceOrientation)
    }

    fun detach() {
        previewLayer.removeFromSuperlayer()
    }
}

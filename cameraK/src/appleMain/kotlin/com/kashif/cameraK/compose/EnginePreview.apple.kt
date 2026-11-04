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
import platform.AVFoundation.AVCaptureVideoOrientation
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeLeft
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeRight
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVCaptureVideoOrientationPortraitUpsideDown
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.CoreGraphics.CGRectZero
import platform.UIKit.UIInterfaceOrientation
import platform.UIKit.UIInterfaceOrientationLandscapeLeft
import platform.UIKit.UIInterfaceOrientationLandscapeRight
import platform.UIKit.UIInterfaceOrientationPortrait
import platform.UIKit.UIInterfaceOrientationPortraitUpsideDown
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
                factory = { PreviewLayerHostView(source.createPreviewLayer()) },
                modifier = Modifier.fillMaxSize(),
                update = {},
                onRelease = { it.detach() },
            )
            if (overlay != null) CameraPreviewOverlay(deviceOrientation, overlay)
        }
    }
}

/**
 * Keeps [previewLayer] the size of the view, and on each layout turns it to the interface's
 * orientation, which a rotation changes. The 1.x facade did this through previewLayoutChanged.
 */
@OptIn(ExperimentalForeignApi::class)
private class PreviewLayerHostView(private val previewLayer: AVCaptureVideoPreviewLayer) :
    UIView(frame = CGRectZero.readValue()) {
    init {
        layer.addSublayer(previewLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        previewLayer.setFrame(bounds)
        val orientation = window?.windowScene?.interfaceOrientation?.toVideoOrientation() ?: return
        previewLayer.connection?.let { connection ->
            if (connection.isVideoOrientationSupported()) connection.videoOrientation = orientation
        }
    }

    fun detach() {
        previewLayer.removeFromSuperlayer()
    }
}

/** The video orientation that matches an interface orientation; null when the interface has none yet. */
private fun UIInterfaceOrientation.toVideoOrientation(): AVCaptureVideoOrientation? = when (this) {
    UIInterfaceOrientationPortrait -> AVCaptureVideoOrientationPortrait
    UIInterfaceOrientationPortraitUpsideDown -> AVCaptureVideoOrientationPortraitUpsideDown
    UIInterfaceOrientationLandscapeLeft -> AVCaptureVideoOrientationLandscapeLeft
    UIInterfaceOrientationLandscapeRight -> AVCaptureVideoOrientationLandscapeRight
    else -> null
}

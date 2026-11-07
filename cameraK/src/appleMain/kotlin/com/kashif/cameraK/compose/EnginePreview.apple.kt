@file:OptIn(CameraKEngineApi::class, InternalCameraKApi::class)

package com.kashif.cameraK.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.engine.layOutPreviewLayer
import com.kashif.cameraK.engine.previewAspectRatio
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.previewAspectRatio
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.InternalCameraKApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.CoreGraphics.CGRectZero
import platform.UIKit.UIInterfaceOrientation
import platform.UIKit.UIInterfaceOrientationLandscapeLeft
import platform.UIKit.UIInterfaceOrientationLandscapeRight
import platform.UIKit.UIInterfaceOrientationPortrait
import platform.UIKit.UIInterfaceOrientationPortraitUpsideDown
import platform.UIKit.UIView

/**
 * Hosts a preview layer of the engine's session in a view of its own, over the whole box, with the
 * overlay over the content rectangle: the box the layer fills, of the engine's aspect ratio turned to
 * the interface orientation the host view reads on each layout pass (null until it has a window
 * scene, when the box's shape decides, as for the layer). It never starts the camera, and composing
 * it twice gives two layers on one session.
 */
@Composable
internal actual fun EnginePreview(
    source: PlatformPreviewSource,
    deviceOrientation: DeviceOrientation,
    modifier: Modifier,
    overlay: (@Composable CameraPreviewScope.() -> Unit)?,
) {
    key(source) {
        var interfaceOrientation by remember { mutableStateOf<DeviceOrientation?>(null) }
        val ratio = remember { source.previewAspectRatio() }
        Box(modifier = modifier) {
            UIKitView(
                factory = {
                    PreviewLayerHostView(source, source.createPreviewLayer()) { interfaceOrientation = it }
                },
                modifier = Modifier.fillMaxSize(),
                update = {},
                onRelease = { it.detach() },
            )
            if (overlay != null) {
                PreviewContent(
                    contentAspect = { box ->
                        ratio?.let {
                            val portraitBox = box.height > box.width
                            val shown = interfaceOrientation
                                ?: if (portraitBox) DeviceOrientation.PORTRAIT else DeviceOrientation.LANDSCAPE_LEFT
                            it.previewAspectRatio(shown)
                        }
                    },
                    deviceOrientation = deviceOrientation,
                    overlay = overlay,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

/**
 * Hosts [previewLayer] and lays it out on each layout pass through [source]: a centred box of the
 * configured aspect ratio, turned by the rotation coordinator from iOS 17, otherwise to the
 * interface's orientation, which a rotation changes. It reports that orientation to
 * [onInterfaceOrientation], portrait or landscape, or null without a window scene, so the overlay
 * turns on the same input as the layer.
 */
@OptIn(ExperimentalForeignApi::class)
private class PreviewLayerHostView(
    private val source: PlatformPreviewSource,
    private val previewLayer: AVCaptureVideoPreviewLayer,
    private val onInterfaceOrientation: (DeviceOrientation?) -> Unit,
) : UIView(frame = CGRectZero.readValue()) {
    private var reported: DeviceOrientation? = null

    init {
        layer.addSublayer(previewLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        val interfaceOrientation = window?.windowScene?.interfaceOrientation
        source.layOutPreviewLayer(previewLayer, bounds, interfaceOrientation)
        val shown = interfaceOrientation?.let(::overlayOrientation)
        if (shown != reported) {
            reported = shown
            onInterfaceOrientation(shown)
        }
    }

    fun detach() {
        previewLayer.removeFromSuperlayer()
    }
}

/** Portrait or landscape for the overlay's ratio, as previewFrame turns the layer; null when unknown. */
@Suppress("DEPRECATION")
private fun overlayOrientation(orientation: UIInterfaceOrientation): DeviceOrientation? = when (orientation) {
    UIInterfaceOrientationPortrait, UIInterfaceOrientationPortraitUpsideDown -> DeviceOrientation.PORTRAIT
    UIInterfaceOrientationLandscapeLeft, UIInterfaceOrientationLandscapeRight -> DeviceOrientation.LANDSCAPE_LEFT
    else -> null
}

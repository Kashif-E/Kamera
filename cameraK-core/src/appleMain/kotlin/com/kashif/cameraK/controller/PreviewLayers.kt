package com.kashif.cameraK.controller

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoOrientation
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.CoreGraphics.CGPointMake
import platform.Foundation.NSThread
import platform.UIKit.UIDevice
import platform.UIKit.UIView
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.Volatile

/**
 * A controller's preview layer: mirrored for the front camera, oriented or turned by the rotation
 * coordinator's preview angle, and removed with the session. The layer belongs to UIKit, so it is read
 * and changed on main. Each seam, when given, replaces the change on the layer's connection.
 */
internal class PreviewLayers(
    mirroring: ((AVCaptureVideoPreviewLayer?, Boolean) -> Unit)?,
    orienting: ((AVCaptureVideoPreviewLayer?, AVCaptureVideoOrientation) -> Unit)?,
    rotating: ((AVCaptureVideoPreviewLayer, Double) -> Unit)?,
    converting: PreviewPointConverter? = null,
) {
    private val mirroring = mirroring ?: ::mirrorPreview
    private val orienting = orienting ?: ::orientPreviewLayer
    private val rotating = rotating ?: ::rotatePreviewLayer
    private val converting = converting ?: PreviewPointConverter(::pointOfInterestThroughConnection)

    /** A UIKit layer: read and written on main only. */
    var layer: AVCaptureVideoPreviewLayer? = null

    @Volatile
    private var lastVideoOrientation: AVCaptureVideoOrientation = AVCaptureVideoOrientationPortrait

    /** Shows [layer] as the preview, mirrored when [front]. Main only. */
    fun adopt(layer: AVCaptureVideoPreviewLayer, front: Boolean) {
        this.layer = layer
        mirroring(layer, front)
    }

    /**
     * Takes the layer away, removes it from its superlayer and takes the session out of it, on main, so
     * a layer UIKit or Compose keeps does not keep the closed session alive; callable from any thread.
     */
    fun remove() {
        val layer = layer
        this.layer = null
        // The layer belongs to UIKit; the engine may close from any thread.
        if (layer != null) {
            val detach = {
                layer.removeFromSuperlayer()
                layer.session = null
            }
            if (NSThread.isMainThread) detach() else dispatch_async(dispatch_get_main_queue(), detach)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    fun setUp(view: UIView, session: AVCaptureSession) {
        val newPreviewLayer = AVCaptureVideoPreviewLayer(session = session).apply {
            // ResizeAspect (letterbox) so the preview shows the exact frame that gets captured — WYSIWYG (#119).
            // ResizeAspectFill would crop the preview to fill the view, mismatching the full-frame captured photo.
            videoGravity = AVLayerVideoGravityResizeAspect
            setFrame(view.bounds)
        }
        orienting(newPreviewLayer, currentVideoOrientation())

        view.layer.addSublayer(newPreviewLayer)
        layer = newPreviewLayer
    }

    /**
     * Main only: the point of interest under content point ([x], [y]) of [layer], the newest adopted:
     * the point scaled into the layer's bounds, which are the content rectangle, then converted by the
     * layer, which accounts for its gravity's crop, its rotation and its mirroring. Null without a layer,
     * with empty bounds, or when the layer cannot convert (no connection).
     */
    @OptIn(ExperimentalForeignApi::class)
    fun devicePoint(x: Float, y: Float): PointOfInterest? {
        val layer = layer ?: return null
        val (layerX, layerY) = layer.bounds.useContents {
            if (size.width <= 0.0 || size.height <= 0.0) return null
            origin.x + x * size.width to origin.y + y * size.height
        }
        return converting.convert(layer, layerX, layerY)
    }

    /** Sets the preview's orientation. Call it on main: the preview layer belongs to UIKit. */
    fun orient(orientation: AVCaptureVideoOrientation) {
        orienting(layer, orientation)
    }

    /** Turns [layer] by [rotation]. Call it on main: the preview layer belongs to UIKit. */
    fun turn(layer: AVCaptureVideoPreviewLayer, rotation: VideoRotation) {
        when (rotation) {
            is VideoRotation.Orientation -> orienting(layer, rotation.orientation)
            is VideoRotation.Angle -> rotating(layer, rotation.degrees)
        }
    }

    /**
     * Mirrors the preview when [mirrored]. Called on the session queue after a swap; the preview layer
     * belongs to UIKit, so it is read and changed on main (AVCam does the same).
     */
    fun mirrorOnMain(mirrored: Boolean) {
        dispatch_async(dispatch_get_main_queue()) {
            mirroring(layer, mirrored)
        }
    }

    fun currentVideoOrientation(): AVCaptureVideoOrientation {
        // FaceUp / FaceDown / Unknown don't correspond to a video orientation. Mapping them to
        // Portrait snaps a landscape preview to portrait and distorts it when the device lies flat
        // (#115). Keep the last valid orientation in those cases so portrait/landscape tracking
        // stays stable as the device tilts (#109).
        lastVideoOrientation = UIDevice.currentDevice.orientation.toVideoOrientation() ?: lastVideoOrientation
        return lastVideoOrientation
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun pointOfInterestThroughConnection(
    layer: AVCaptureVideoPreviewLayer,
    x: Double,
    y: Double,
): PointOfInterest? {
    if (layer.connection == null) return null
    return layer.captureDevicePointOfInterestForPoint(CGPointMake(x, y)).useContents { PointOfInterest(this.x, this.y) }
}

private fun rotatePreviewLayer(layer: AVCaptureVideoPreviewLayer, degrees: Double) {
    layer.connection?.turn(VideoRotation.Angle(degrees))
}

private fun orientPreviewLayer(layer: AVCaptureVideoPreviewLayer?, orientation: AVCaptureVideoOrientation) {
    layer?.connection?.let { connection ->
        if (connection.isVideoOrientationSupported()) connection.videoOrientation = orientation
    }
}

private fun mirrorPreview(layer: AVCaptureVideoPreviewLayer?, mirrored: Boolean) {
    layer?.connection?.let { connection ->
        if (connection.isVideoMirroringSupported()) {
            connection.automaticallyAdjustsVideoMirroring = false
            connection.setVideoMirrored(mirrored)
        }
    }
}

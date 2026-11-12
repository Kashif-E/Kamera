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
import platform.QuartzCore.CATransaction
import platform.UIKit.UIDevice
import platform.UIKit.UIView
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.Volatile

/**
 * A controller's preview layer: mirrored for the front camera, oriented or turned by the rotation
 * coordinator's preview angle, and removed with the session. The layer belongs to UIKit, so it is read
 * and changed on main, except its session. Each seam, when given, replaces the change on the layer's
 * connection.
 *
 * Binding a layer to the session, and taking the session out of it, configures the session. On main,
 * while [queue] had begun a configuration, the two took AVFoundation's locks in opposite orders and
 * neither returned (an iPhone 14 Pro stalled in Starting, main in `_addVideoPreviewLayer:` and the
 * session queue in `beginConfiguration`), so both run on [queue].
 */
internal class PreviewLayers(
    private val queue: SessionQueue,
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

    /** The turn last given to [layer], given again once the layer is bound. Main only. */
    private var lastTurn: VideoRotation? = null

    @Volatile
    private var lastVideoOrientation: AVCaptureVideoOrientation = AVCaptureVideoOrientationPortrait

    /**
     * Shows [layer] as the preview of [session], mirrored when [front] says so. Main only. The layer
     * is bound on the session queue, after the session work already queued; bound, it has a
     * connection, and main mirrors it and gives it its last turn again.
     */
    fun adopt(layer: AVCaptureVideoPreviewLayer, session: AVCaptureSession?, front: () -> Boolean) {
        this.layer = layer
        lastTurn = null
        if (session == null) {
            mirroring(layer, front())
            return
        }
        bind(layer, session, front)
    }

    private fun bind(layer: AVCaptureVideoPreviewLayer, session: AVCaptureSession, front: (() -> Boolean)?) {
        queue.async {
            withoutAnimation { layer.session = session }
            val mirrored = front?.invoke()
            dispatch_async(dispatch_get_main_queue()) {
                if (this.layer === layer) {
                    mirrored?.let { mirroring(layer, it) }
                    lastTurn?.let { turn(layer, it) }
                }
            }
        }
    }

    /**
     * Ends [layer] once nothing shows it: takes the session out of it on the session queue, so it is
     * not freed on main still bound. Main only. A session shows one layer at a time, so a newer layer
     * the session shows instead keeps it.
     */
    fun release(layer: AVCaptureVideoPreviewLayer) {
        if (this.layer === layer) {
            this.layer = null
            lastTurn = null
        }
        queue.async {
            withoutAnimation { if (layer.session != null) layer.session = null }
        }
    }

    /**
     * Takes the layer away: removes it from its superlayer on main, then takes the session out of it on
     * the session queue, so a layer UIKit or Compose keeps does not keep the closed session alive;
     * callable from any thread. [then] runs on the session queue once the session is out of the layer
     * (at once, on the calling thread, without a layer): taking it out configures the session, and
     * stopping the session inside that configuration raises an exception Kotlin cannot catch.
     */
    fun remove(then: () -> Unit = {}) {
        val layer = layer
        this.layer = null
        if (layer == null) {
            then()
            return
        }
        // The layer belongs to UIKit; the engine may close from any thread.
        val detach = {
            layer.removeFromSuperlayer()
            queue.async {
                withoutAnimation { layer.session = null }
                then()
            }
        }
        if (NSThread.isMainThread) detach() else dispatch_async(dispatch_get_main_queue(), detach)
    }

    @OptIn(ExperimentalForeignApi::class)
    fun setUp(view: UIView, session: AVCaptureSession) {
        val newPreviewLayer = AVCaptureVideoPreviewLayer().apply {
            // ResizeAspect (letterbox) so the preview shows the exact frame that gets captured — WYSIWYG (#119).
            // ResizeAspectFill would crop the preview to fill the view, mismatching the full-frame captured photo.
            videoGravity = AVLayerVideoGravityResizeAspect
            setFrame(view.bounds)
        }

        view.layer.addSublayer(newPreviewLayer)
        layer = newPreviewLayer
        lastTurn = VideoRotation.Orientation(currentVideoOrientation())
        bind(newPreviewLayer, session, front = null)
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
        if (layer === this.layer) lastTurn = rotation
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

/** Runs [change] in its own Core Animation transaction, without implicit animations, off main too. */
private inline fun withoutAnimation(change: () -> Unit) {
    CATransaction.begin()
    CATransaction.setDisableActions(true)
    try {
        change()
    } finally {
        CATransaction.commit()
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

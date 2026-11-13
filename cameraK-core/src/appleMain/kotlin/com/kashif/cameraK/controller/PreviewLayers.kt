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
 *
 * A session shows one layer at a time: binding a second takes the session out of the first. Two
 * previews of one holder, as in a crossfade or a preview in a dialog over a screen's, show the camera
 * in the newer; once it is released, the session goes back to the newest one left.
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

    /** The layer the session shows, the newest of [shown]. A UIKit layer: changed on main, read by [remove] too. */
    @Volatile
    var layer: AVCaptureVideoPreviewLayer? = null

    /** The layers adopted and not yet released, oldest first, with the turn each was given last. Main only. */
    private val shown = mutableListOf<Shown>()

    private class Shown(val layer: AVCaptureVideoPreviewLayer, var turn: VideoRotation? = null)

    /**
     * Set by [remove]: a layer adopted afterwards, by a preview composed as the engine closes, is not
     * bound to the session the close tears down.
     */
    @Volatile
    private var closed = false

    @Volatile
    private var lastVideoOrientation: AVCaptureVideoOrientation = AVCaptureVideoOrientationPortrait

    /**
     * Shows [layer] as the preview of [session], mirrored when [front] says so. Main only. The layer
     * is bound on the session queue, after the session work already queued; bound, it has a
     * connection, and main mirrors it and gives it its last turn again.
     */
    fun adopt(layer: AVCaptureVideoPreviewLayer, session: AVCaptureSession?, front: () -> Boolean) {
        // Written before [closed] is read, as [remove] writes [closed] before it reads [layer]: either the
        // close finds this layer and takes it away, or this sees the close.
        this.layer = layer
        if (closed) {
            this.layer = null
            return
        }
        shown.removeAll { it.layer === layer }
        shown += Shown(layer)
        if (session == null) {
            mirroring(layer, front())
            return
        }
        bind(layer, session, front)
    }

    private fun bind(layer: AVCaptureVideoPreviewLayer, session: AVCaptureSession, front: (() -> Boolean)?) {
        queue.async {
            if (layer.session !== session) withoutAnimation { layer.session = session }
            val mirrored = front?.invoke()
            dispatch_async(dispatch_get_main_queue()) {
                if (this.layer === layer) {
                    mirrored?.let { mirroring(layer, it) }
                    shown.lastOrNull { it.layer === layer }?.turn?.let { turn(layer, it) }
                }
            }
        }
    }

    /**
     * Ends [layer] once nothing shows it: takes the session out of it on the session queue, so it is
     * not freed on main still bound. Main only. When the session showed [layer], it goes back to the
     * newest layer left, bound to [session] again, mirrored when [front] says so and given its last
     * turn; that layer is returned. A newer layer the session shows instead keeps it.
     */
    fun release(
        layer: AVCaptureVideoPreviewLayer,
        session: AVCaptureSession?,
        front: () -> Boolean,
    ): AVCaptureVideoPreviewLayer? {
        val showing = this.layer === layer
        shown.removeAll { it.layer === layer }
        var restored: AVCaptureVideoPreviewLayer? = null
        if (showing) {
            restored = shown.lastOrNull()?.layer
            this.layer = restored
            if (restored != null && session != null && !closed) bind(restored, session, front) else restored = null
        }
        queue.async {
            withoutAnimation { if (layer.session != null) layer.session = null }
        }
        return restored
    }

    /**
     * Takes the layer away: removes it from its superlayer on main, then takes the session out of it on
     * the session queue, so a layer UIKit or Compose keeps does not keep the closed session alive;
     * callable from any thread. [then] runs on the session queue once the session is out of the layer
     * (at once, on the calling thread, without a layer): taking it out configures the session, and
     * stopping the session inside that configuration raises an exception Kotlin cannot catch.
     */
    fun remove(then: () -> Unit = {}) {
        closed = true
        val layer = layer
        this.layer = null
        if (layer == null) {
            then()
            return
        }
        // The layer belongs to UIKit; the engine may close from any thread.
        val detach = {
            layer.removeFromSuperlayer()
            this.layer = null
            val older = shown.map { it.layer }.filter { it !== layer }
            shown.clear()
            queue.async {
                withoutAnimation {
                    layer.session = null
                    older.forEach { if (it.session != null) it.session = null }
                }
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
        closed = false
        layer = newPreviewLayer
        shown.clear()
        shown += Shown(newPreviewLayer, VideoRotation.Orientation(currentVideoOrientation()))
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
        shown.lastOrNull { it.layer === layer }?.turn = rotation
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

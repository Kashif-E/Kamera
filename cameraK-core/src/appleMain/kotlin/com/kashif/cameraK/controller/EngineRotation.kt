package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFoundation.AVCaptureVideoOrientation
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIDeviceOrientationDidChangeNotification
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private const val TAG = "CameraK"
private const val ROTATION_COORDINATOR_IOS = 17
private const val DEGREES_90 = 90
private const val DEGREES_180 = 180
private const val DEGREES_270 = 270

/**
 * How an iOS engine's photos, movies, frames and preview are turned (design §2.4): the target
 * orientation captures and movies are locked to, the device orientation observed between start and
 * stop, and from iOS 17 the rotation coordinator of the bound camera ([boundDevice]) and the newest
 * preview layer. Its state is kept under [lock].
 */
internal class EngineRotation(
    private val lock: EngineLock,
    private val generations: EngineGenerations,
    private val custom: CustomCameraController,
    osVersion: Int,
    private val readDeviceOrientation: () -> UIDeviceOrientation,
    private val generateOrientationNotifications: (Boolean) -> Unit,
    private val rotationSources: RotationSourceFactory,
    private val boundDevice: () -> CaptureDeviceOps?,
) {
    private val center = NSNotificationCenter.defaultCenter

    private val _deviceOrientation = MutableStateFlow(DeviceOrientation.PORTRAIT)
    val deviceOrientation: StateFlow<DeviceOrientation> = _deviceOrientation.asStateFlow()

    /** The orientation captures and movies are locked to; null follows the device. */
    private val target = atomic<DeviceOrientation?>(null)
    var targetOrientation: DeviceOrientation?
        get() = target.value
        set(value) {
            target.value = value
        }

    private var orientationObserver: Any? = null

    /** The rotation coordinator of the bound camera and the newest preview layer, iOS 17 and later. Under [lock]. */
    private var rotation: Rotation? = null

    /** Counts binds and unbinds; a coordinator is made only for the latest. Under [lock]. */
    private var bindings = 0L

    /** The preview layer made last, which the rotation coordinator turns. Under [lock]. */
    private var newestPreviewLayer: AVCaptureVideoPreviewLayer? = null

    /** The device orientation captures took last, kept while the device lies flat (#115, #109). */
    private val lastDeviceVideoOrientation = atomic(AVCaptureVideoOrientationPortrait)

    /** The coordinator made for [device] and [layer]. */
    private class Rotation(
        val device: CaptureDeviceOps,
        val layer: AVCaptureVideoPreviewLayer?,
        val source: RotationSource,
    )

    private val coordinatesRotation: Boolean = osVersion >= ROTATION_COORDINATOR_IOS

    /** A new preview layer: from iOS 17 the coordinator is bound again for it on the session queue. */
    fun adoptPreviewLayer(layer: AVCaptureVideoPreviewLayer) {
        if (!coordinatesRotation) return
        lock.locked { newestPreviewLayer = layer }
        custom.afterQueuedSessionWork {
            platformCallback("binding the rotation coordinator") { bindOnQueue() }
        }
    }

    /** The orientation captures and movies take: the target when one is set, otherwise the device's. */
    fun captureVideoOrientation(): AVCaptureVideoOrientation =
        target.value?.toVideoOrientation() ?: deviceVideoOrientation()

    /**
     * How photos and movies are turned (design §2.4): the target orientation when one is set, else the
     * rotation coordinator's horizon-level capture angle from iOS 17, else the device's orientation.
     */
    fun captureRotation(): VideoRotation {
        target.value?.let { return VideoRotation.Orientation(it.toVideoOrientation()) }
        lock.locked { rotation }?.let { return VideoRotation.Angle(it.source.captureAngle) }
        return VideoRotation.Orientation(deviceVideoOrientation())
    }

    /**
     * How the device is held, as a video orientation. Face up, face down and unknown name none, and
     * mapping them to portrait would snap a landscape capture to portrait while the device lies flat
     * (#115), so they keep the last one (#109).
     */
    private fun deviceVideoOrientation(): AVCaptureVideoOrientation {
        val now = readDeviceOrientation().toVideoOrientation() ?: return lastDeviceVideoOrientation.value
        lastDeviceVideoOrientation.value = now
        return now
    }

    /** Main thread: [layer] by the coordinator's preview angle when it drives the layer, else to [interfaceOrientation]. */
    fun turnPreview(layer: AVCaptureVideoPreviewLayer, interfaceOrientation: AVCaptureVideoOrientation?) {
        val driven = lock.locked { rotation }?.takeIf { it.layer === layer }
        val turn = when {
            driven != null -> VideoRotation.Angle(driven.source.previewAngle)
            interfaceOrientation != null -> VideoRotation.Orientation(interfaceOrientation)
            else -> return
        }
        custom.turnPreview(layer, turn)
    }

    /**
     * Clockwise degrees that turn a frame upright: the video data output delivers the sensor's
     * landscape buffer, which the back camera shows upright with the device turned left and the front
     * camera with it turned right (the front's mirroring is not part of the rotation).
     */
    fun frameRotationDegrees(): Int {
        val front = custom.currentDevice?.isFront == true
        return when (_deviceOrientation.value) {
            DeviceOrientation.PORTRAIT -> if (front) DEGREES_270 else DEGREES_90
            DeviceOrientation.LANDSCAPE_LEFT -> if (front) DEGREES_180 else 0
            DeviceOrientation.PORTRAIT_UPSIDE_DOWN -> if (front) DEGREES_90 else DEGREES_270
            DeviceOrientation.LANDSCAPE_RIGHT -> if (front) 0 else DEGREES_180
        }
    }

    // ─── Rotation coordinator (iOS 17 and later) ────────────────────────────────────────────────

    /**
     * Session queue: a coordinator for the bound camera and the newest preview layer, in place of one
     * made for another camera or layer, and the layer turned to its preview angle. Nothing below iOS
     * 17, before a camera is bound, or for a camera AVFoundation does not own.
     *
     * The coordinator is made on main, as AVCam makes it: its init waits for the main thread, so made
     * here it deadlocks with a main thread waiting on the session, as making a preview layer does
     * (iPhone 14 Pro, iOS 27.3). A later bind or an unbind before main gets to it supersedes it.
     */
    fun bindOnQueue() {
        if (!coordinatesRotation) return
        val device = boundDevice() ?: return
        val (binding, layer) = lock.locked {
            val current = rotation
            if (current != null && current.device === device && current.layer === newestPreviewLayer) return
            ++bindings to newestPreviewLayer
        }
        dispatch_async(dispatch_get_main_queue()) {
            platformCallback("binding the rotation coordinator") { bindOnMain(binding, device, layer) }
        }
    }

    private fun bindOnMain(binding: Long, device: CaptureDeviceOps, layer: AVCaptureVideoPreviewLayer?) {
        if (lock.locked { binding != bindings || generations.closedLocked }) return
        val made = atomic<Rotation?>(null)
        val source = try {
            rotationSources.make(device, layer) { angle ->
                made.value?.let { platformCallback("a preview angle report") { onPreviewAngle(it, angle) } }
            }
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "making the rotation coordinator failed; rotation follows the device", e)
            null
        }
        val next = source?.let { Rotation(device, layer, it) }
        made.value = next
        val replaced = lock.locked {
            if (binding != bindings || generations.closedLocked) {
                next?.source?.close()
                return
            }
            rotation.also { rotation = next }
        }
        replaced?.source?.close()
        if (next?.layer != null) onPreviewAngle(next, next.source.previewAngle)
    }

    /** Session queue: the coordinator closed, so nothing reports angles for a camera that stopped. */
    fun unbindOnQueue() {
        lock.locked {
            bindings++
            rotation.also { rotation = null }
        }?.source?.close()
    }

    /** From any thread: [made]'s layer turned to [angle] on main, unless another coordinator replaced it since. */
    private fun onPreviewAngle(made: Rotation, angle: Double) {
        val layer = made.layer ?: return
        dispatch_async(dispatch_get_main_queue()) {
            platformCallback("turning the preview") {
                if (lock.locked { rotation === made }) custom.turnPreview(layer, VideoRotation.Angle(angle))
            }
        }
    }

    // ─── Device orientation ─────────────────────────────────────────────────────────────────────

    /** From start until stop or close: UIDeviceOrientationDidChangeNotification, read through the reader seam. */
    fun startObservingOrientation() {
        val added = lock.locked {
            if (generations.closedLocked || orientationObserver != null) return@locked false
            orientationObserver = center.addObserverForName(UIDeviceOrientationDidChangeNotification, null, null) {
                platformCallback("a device orientation change") { updateDeviceOrientation() }
            }
            true
        }
        if (!added) return
        generateOrientationNotifications(true)
        platformCallback("reading the device orientation") { updateDeviceOrientation() }
    }

    fun stopObservingOrientation() {
        val observer = lock.locked { orientationObserver.also { orientationObserver = null } } ?: return
        center.removeObserver(observer)
        generateOrientationNotifications(false)
    }

    /** Face up, face down and unknown name no orientation, so they keep the last one. */
    private fun updateDeviceOrientation() {
        val orientation = readDeviceOrientation().toDeviceOrientation() ?: return
        lock.locked {
            if (!generations.closedLocked) _deviceOrientation.value = orientation
        }
    }
}

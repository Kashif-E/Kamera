package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFoundation.*
import platform.CoreGraphics.CGPointMake
import platform.CoreMedia.CMVideoFormatDescriptionGetDimensions
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSValue

/**
 * The camera-device operations [CustomCameraController] performs. Production wraps an
 * [AVCaptureDevice]; tests substitute a device that records each call, since the simulator has no
 * camera.
 */
internal interface CaptureDeviceOps {
    /** AVFoundation's uniqueID, which is the camera's `LensInfo.id`. */
    val uniqueId: String
    val isFront: Boolean
    val hasTorch: Boolean
    val torchMode: AVCaptureTorchMode

    /** Whether the torch is lit: false while torchMode is on but the system has shut it off (thermal). */
    val isTorchActive: Boolean get() = torchMode == AVCaptureTorchModeOn
    val videoZoomFactor: Double

    /**
     * The zoom range: AVFoundation raises an exception nothing can catch below 1.0 and above the active
     * format's maximum ([maxVideoZoomFactor]), and clamps a factor between 1.0 and the format's maximum
     * into [minVideoZoomFactor]..[maxAvailableVideoZoomFactor] (AVCaptureDevice.h). The available range
     * is narrower only on a virtual camera delivering depth data. The engine clamps into the available
     * range, so the ratio it returns and reports is the one the camera takes.
     */
    val minVideoZoomFactor: Double get() = 1.0
    val maxVideoZoomFactor: Double
    val maxAvailableVideoZoomFactor: Double get() = maxVideoZoomFactor

    /** The photo sizes the camera's active format can deliver. */
    val supportedMaxPhotoDimensions: List<PhotoDimensions>

    fun isTorchModeSupported(mode: AVCaptureTorchMode): Boolean

    /** Whether the device can deliver the session preset [preset] (an AVCaptureSessionPreset). */
    fun supportsSessionPreset(preset: String): Boolean

    /** Takes the device's configuration lock; returns what prevented it, or null when the lock is held. */
    fun lockForConfiguration(): Throwable?

    fun unlockForConfiguration()

    fun setTorchMode(mode: AVCaptureTorchMode)

    fun setVideoZoomFactor(factor: Double)

    /**
     * Calls [onChange], on whatever thread reports it, each time the torch or the zoom changes, as
     * key-value observing of the device reports it. Closing the result stops the observation.
     */
    fun observeHardware(onChange: () -> Unit): AutoCloseable

    val isFocusPointOfInterestSupported: Boolean
    val isExposurePointOfInterestSupported: Boolean

    fun isFocusModeSupported(mode: AVCaptureFocusMode): Boolean

    fun isExposureModeSupported(mode: AVCaptureExposureMode): Boolean

    /** Whether the camera is focusing now. */
    val isAdjustingFocus: Boolean

    /** The bias range `setExposureTargetBias` takes; AVFoundation raises an exception nothing can catch outside it. */
    val minExposureTargetBias: Float
    val maxExposureTargetBias: Float
    val exposureTargetBias: Float

    /** The width and height the active format delivers, in the sensor's landscape orientation; null when unknown. */
    val activeFormatDimensions: PhotoDimensions?

    fun setFocusPointOfInterest(point: PointOfInterest)

    fun setFocusMode(mode: AVCaptureFocusMode)

    fun setExposurePointOfInterest(point: PointOfInterest)

    fun setExposureMode(mode: AVCaptureExposureMode)

    fun setSubjectAreaChangeMonitoringEnabled(enabled: Boolean)

    /** Sets the bias; [completion] runs, on whatever thread AVFoundation picks, once frames carry it. */
    fun setExposureTargetBias(bias: Float, completion: () -> Unit)

    /** Calls [onChange], on whatever thread reports it, each time [isAdjustingFocus] changes. */
    fun observeAdjustingFocus(onChange: () -> Unit): AutoCloseable

    /** Calls [onChange], on whatever thread posts it, on each AVCaptureDeviceSubjectAreaDidChangeNotification. */
    fun observeSubjectAreaChange(onChange: () -> Unit): AutoCloseable

    /**
     * A session input that captures from this device, or why one cannot be made: for a real device,
     * an [NSErrorException] carrying AVFoundation's error (a camera the user has not authorised, one
     * in use by another app).
     */
    fun makeInput(): Result<AVCaptureInput>
}

/** Finds the cameras [CustomCameraController] can switch between. */
internal fun interface CameraDeviceProvider {
    /** A camera of [type] facing [lens], or facing either way when [lens] is null; null when there is none. */
    fun find(lens: CameraLens?, type: CameraDeviceType): CaptureDeviceOps?

    /** The camera whose uniqueID is [id]; null when there is none. */
    fun findById(id: String): CaptureDeviceOps? = null
}

@OptIn(ExperimentalForeignApi::class)
internal class AVCaptureDeviceOps(val device: AVCaptureDevice) : CaptureDeviceOps {
    override val uniqueId: String get() = device.uniqueID
    override val isFront: Boolean get() = device.position == AVCaptureDevicePositionFront
    override val hasTorch: Boolean get() = device.hasTorch
    override val torchMode: AVCaptureTorchMode get() = device.torchMode
    override val isTorchActive: Boolean get() = device.isTorchActive()
    override val videoZoomFactor: Double get() = device.videoZoomFactor
    override val minVideoZoomFactor: Double get() = device.minAvailableVideoZoomFactor
    override val maxVideoZoomFactor: Double get() = device.activeFormat.videoMaxZoomFactor
    override val maxAvailableVideoZoomFactor: Double get() = device.maxAvailableVideoZoomFactor
    override val supportedMaxPhotoDimensions: List<PhotoDimensions>
        get() = device.activeFormat.supportedMaxPhotoDimensions
            .mapNotNull { (it as? NSValue)?.CMVideoDimensionsValue?.toPhotoDimensions() }

    override fun isTorchModeSupported(mode: AVCaptureTorchMode): Boolean = device.isTorchModeSupported(mode)

    override fun supportsSessionPreset(preset: String): Boolean = device.supportsAVCaptureSessionPreset(preset)

    override fun lockForConfiguration(): Throwable? = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        if (device.lockForConfiguration(error.ptr)) {
            null
        } else {
            error.value?.let(::NSErrorException)
                ?: IllegalStateException("lockForConfiguration failed and gave no error")
        }
    }

    override fun unlockForConfiguration() = device.unlockForConfiguration()

    override fun setTorchMode(mode: AVCaptureTorchMode) {
        device.torchMode = mode
    }

    override fun setVideoZoomFactor(factor: Double) {
        device.videoZoomFactor = factor
    }

    override fun observeHardware(onChange: () -> Unit): AutoCloseable =
        observeKeyPaths(device, listOf("torchMode", "torchActive", "videoZoomFactor"), onChange)

    override val isFocusPointOfInterestSupported: Boolean get() = device.isFocusPointOfInterestSupported()
    override val isExposurePointOfInterestSupported: Boolean get() = device.isExposurePointOfInterestSupported()

    override fun isFocusModeSupported(mode: AVCaptureFocusMode): Boolean = device.isFocusModeSupported(mode)

    override fun isExposureModeSupported(mode: AVCaptureExposureMode): Boolean = device.isExposureModeSupported(mode)

    override val isAdjustingFocus: Boolean get() = device.isAdjustingFocus()
    override val minExposureTargetBias: Float get() = device.minExposureTargetBias
    override val maxExposureTargetBias: Float get() = device.maxExposureTargetBias
    override val exposureTargetBias: Float get() = device.exposureTargetBias
    override val activeFormatDimensions: PhotoDimensions?
        get() = device.activeFormat.formatDescription
            ?.let { CMVideoFormatDescriptionGetDimensions(it).toPhotoDimensions() }
            ?.takeIf { it.width > 0 && it.height > 0 }

    override fun setFocusPointOfInterest(point: PointOfInterest) {
        device.focusPointOfInterest = CGPointMake(point.x, point.y)
    }

    override fun setFocusMode(mode: AVCaptureFocusMode) {
        device.focusMode = mode
    }

    override fun setExposurePointOfInterest(point: PointOfInterest) {
        device.exposurePointOfInterest = CGPointMake(point.x, point.y)
    }

    override fun setExposureMode(mode: AVCaptureExposureMode) {
        device.exposureMode = mode
    }

    override fun setSubjectAreaChangeMonitoringEnabled(enabled: Boolean) {
        device.subjectAreaChangeMonitoringEnabled = enabled
    }

    override fun setExposureTargetBias(bias: Float, completion: () -> Unit) =
        device.setExposureTargetBias(bias) { _ -> completion() }

    override fun observeAdjustingFocus(onChange: () -> Unit): AutoCloseable =
        observeKeyPaths(device, listOf("adjustingFocus"), onChange)

    override fun observeSubjectAreaChange(onChange: () -> Unit): AutoCloseable {
        val center = NSNotificationCenter.defaultCenter
        val token = center.addObserverForName(AVCaptureDeviceSubjectAreaDidChangeNotification, device, null) {
            onChange()
        }
        val removed = atomic(false)
        return AutoCloseable { if (removed.compareAndSet(expect = false, update = true)) center.removeObserver(token) }
    }

    override fun makeInput(): Result<AVCaptureInput> = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        AVCaptureDeviceInput.deviceInputWithDevice(device, error.ptr)?.let { Result.success(it) }
            ?: Result.failure(
                error.value?.let(::NSErrorException)
                    ?: IllegalStateException("AVFoundation made no input for the camera and gave no error"),
            )
    }
}

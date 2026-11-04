package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFoundation.*
import platform.Foundation.NSError
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

    /** The zoom range a set must stay inside: AVFoundation raises an uncatchable exception outside it. */
    val minVideoZoomFactor: Double get() = 1.0
    val maxVideoZoomFactor: Double

    /** The photo sizes the camera's active format can deliver. */
    val supportedMaxPhotoDimensions: List<PhotoDimensions>

    fun isTorchModeSupported(mode: AVCaptureTorchMode): Boolean

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
    override val supportedMaxPhotoDimensions: List<PhotoDimensions>
        get() = device.activeFormat.supportedMaxPhotoDimensions
            .mapNotNull { (it as? NSValue)?.CMVideoDimensionsValue?.toPhotoDimensions() }

    override fun isTorchModeSupported(mode: AVCaptureTorchMode): Boolean = device.isTorchModeSupported(mode)

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

    override fun makeInput(): Result<AVCaptureInput> = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        AVCaptureDeviceInput.deviceInputWithDevice(device, error.ptr)?.let { Result.success(it) }
            ?: Result.failure(
                error.value?.let(::NSErrorException)
                    ?: IllegalStateException("AVFoundation made no input for the camera and gave no error"),
            )
    }
}

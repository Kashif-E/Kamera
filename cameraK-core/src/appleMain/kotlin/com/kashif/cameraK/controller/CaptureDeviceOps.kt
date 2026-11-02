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
    val isFront: Boolean
    val hasTorch: Boolean
    val torchMode: AVCaptureTorchMode
    val videoZoomFactor: Double
    val maxVideoZoomFactor: Double

    /** The photo sizes the camera's active format can deliver (iOS 16+); empty below iOS 16. */
    val supportedMaxPhotoDimensions: List<PhotoDimensions>

    fun isTorchModeSupported(mode: AVCaptureTorchMode): Boolean

    /** Takes the device's configuration lock; returns what prevented it, or null when the lock is held. */
    fun lockForConfiguration(): Throwable?

    fun unlockForConfiguration()

    fun setTorchMode(mode: AVCaptureTorchMode)

    fun setVideoZoomFactor(factor: Double)

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
}

@OptIn(ExperimentalForeignApi::class)
internal class AVCaptureDeviceOps(val device: AVCaptureDevice) : CaptureDeviceOps {
    override val isFront: Boolean get() = device.position == AVCaptureDevicePositionFront
    override val hasTorch: Boolean get() = device.hasTorch
    override val torchMode: AVCaptureTorchMode get() = device.torchMode
    override val videoZoomFactor: Double get() = device.videoZoomFactor
    override val maxVideoZoomFactor: Double get() = device.activeFormat.videoMaxZoomFactor
    override val supportedMaxPhotoDimensions: List<PhotoDimensions>
        get() = if (isOsAtLeast(16)) {
            device.activeFormat.supportedMaxPhotoDimensions
                .mapNotNull { (it as? NSValue)?.CMVideoDimensionsValue?.toPhotoDimensions() }
        } else {
            emptyList()
        }

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

    override fun makeInput(): Result<AVCaptureInput> = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        AVCaptureDeviceInput.deviceInputWithDevice(device, error.ptr)?.let { Result.success(it) }
            ?: Result.failure(
                error.value?.let(::NSErrorException)
                    ?: IllegalStateException("AVFoundation made no input for the camera and gave no error"),
            )
    }
}

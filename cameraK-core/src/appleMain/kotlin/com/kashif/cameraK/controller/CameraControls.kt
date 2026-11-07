package com.kashif.cameraK.controller

import com.kashif.cameraK.controller.CustomCameraController.CameraException
import com.kashif.cameraK.utils.CameraKLogger
import platform.AVFoundation.AVCaptureTorchMode
import platform.AVFoundation.AVCaptureTorchModeOff
import kotlin.concurrent.Volatile

private const val TAG = "CameraK"

/**
 * A controller's torch and zoom on the camera in use ([currentCamera]): every device property change
 * runs on [queue] under the camera's configuration lock, and a refused lock goes to [reportError].
 */
internal class CameraControls(
    private val queue: SessionQueue,
    private val currentCamera: () -> CaptureDeviceOps?,
    private val reportError: (CameraException) -> Unit,
) {
    /** The torch mode applied to the camera in use whenever the session starts or the lens changes. */
    @Volatile
    var torchMode: AVCaptureTorchMode = AVCaptureTorchModeOff

    /** The zoom last requested, so getZoom answers at once while the change runs on the session queue. */
    @Volatile
    private var requestedZoom: Float? = null

    /** Forgets the zoom last requested, when the camera in use changes or the session is released. */
    fun forgetRequestedZoom() {
        requestedZoom = null
    }

    fun setTorchMode(mode: AVCaptureTorchMode) {
        torchMode = mode
        queue.async {
            currentCamera()?.let { applyTorchMode(it, mode) }
        }
    }

    /**
     * Sets [mode] on [camera], which must have a torch that supports it, under its configuration lock.
     * Session queue only; returns why the camera could not be locked.
     */
    fun setTorchOnQueue(camera: CaptureDeviceOps, mode: AVCaptureTorchMode): CameraException? =
        withConfigurationLock(camera, "set the torch") { camera.setTorchMode(mode) }

    /**
     * Sets [factor], which must lie in [camera]'s zoom range, under its configuration lock. Session queue
     * only; returns why the camera could not be locked.
     */
    fun setZoomOnQueue(camera: CaptureDeviceOps, factor: Double): CameraException? =
        withConfigurationLock(camera, "set the zoom") { camera.setVideoZoomFactor(factor) }

    /** Applies [torchMode] to the camera in use unless it is already set. Runs on the session queue. */
    fun applyConfiguredTorch() {
        val camera = currentCamera() ?: return
        val mode = torchMode
        if (camera.torchMode != mode) applyTorchMode(camera, mode)
    }

    /** Sets [mode] on [camera] when it has a torch that supports it. Runs on the session queue. */
    private fun applyTorchMode(camera: CaptureDeviceOps, mode: AVCaptureTorchMode) {
        if (!camera.hasTorch) return
        if (!camera.isTorchModeSupported(mode)) {
            CameraKLogger.w(TAG, "the camera does not support torch mode $mode; leaving the torch as it is")
            return
        }
        withConfigurationLock(camera, "set the torch") { camera.setTorchMode(mode) }
    }

    /**
     * Runs [change] holding [camera]'s configuration lock, which AVFoundation requires for every
     * device property change; a refused lock is reported, the change skipped and the failure
     * returned. Runs on the session queue.
     */
    private inline fun withConfigurationLock(
        camera: CaptureDeviceOps,
        operation: String,
        change: () -> Unit,
    ): CameraException? {
        val refused = camera.lockForConfiguration()
        if (refused != null) {
            CameraKLogger.e(TAG, "could not lock the camera to $operation", refused)
            val failure = CameraException.ConfigurationError("Could not lock the camera to $operation", refused)
            reportError(failure)
            return failure
        }
        try {
            change()
        } finally {
            camera.unlockForConfiguration()
        }
        return null
    }

    /**
     * Sets the zoom level smoothly.
     * @param zoomFactor The desired zoom level (1.0 = no zoom), clamped to [1, getMaxZoom()]
     */
    fun setZoom(zoomFactor: Float) {
        val camera = currentCamera() ?: return
        val clampedZoom = zoomFactor.coerceIn(1.0f, getMaxZoom())
        requestedZoom = clampedZoom
        queue.async {
            if (currentCamera() !== camera) return@async
            withConfigurationLock(camera, "set the zoom") { camera.setVideoZoomFactor(clampedZoom.toDouble()) }
        }
    }

    /**
     * Gets the current zoom level.
     * @return Current zoom factor (1.0 = no zoom)
     */
    fun getZoom(): Float = requestedZoom ?: currentCamera()?.videoZoomFactor?.toFloat() ?: 1.0f

    /**
     * Gets the maximum zoom level supported by the current camera.
     * @return Maximum zoom factor
     */
    fun getMaxZoom(): Float = currentCamera()?.maxAvailableVideoZoomFactor?.toFloat() ?: 1.0f
}

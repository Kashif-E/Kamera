package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.video.VideoCaptureResult
import com.kashif.cameraK.video.VideoConfiguration

/**
 * Interface defining the core functionalities of the CameraController.
 */
expect class CameraController {

    /**
     * Captures an image and saves it directly to a file.
     *
     * @return ImageCaptureResult.SuccessWithFile containing the file path, or an error result
     */
    suspend fun takePictureToFile(): ImageCaptureResult

    /**
     * Toggles the flash mode between ON, OFF, and AUTO.
     */
    fun toggleFlashMode()

    /**
     * Sets the flash mode of the camera
     *
     * @param mode The desired [FlashMode]
     */
    fun setFlashMode(mode: FlashMode)

    /**
     * @return the current [FlashMode] of the camera, if available
     */
    fun getFlashMode(): FlashMode?

    /**
     * Toggles the torch mode between ON, OFF, and AUTO.
     *
     * Note: On Android, AUTO mode is not natively supported by CameraX and will be treated as ON.
     * iOS supports AUTO mode natively through AVFoundation.
     */
    fun toggleTorchMode()

    /**
     * Sets the torch mode of the camera
     *
     * @param mode The desired [TorchMode]
     *
     * Note: On Android, TorchMode.AUTO is not natively supported by CameraX and will be treated as ON.
     * iOS supports AUTO mode natively through AVFoundation.
     */
    fun setTorchMode(mode: TorchMode)

    /**
     * Gets the current torch mode.
     *
     * @return The current [TorchMode] (ON, OFF, AUTO), or null if not available
     *
     * Note: Desktop does not support torch mode and will always return null.
     */
    fun getTorchMode(): TorchMode?

    /**
     * Toggles the camera lens between FRONT and BACK.
     *
     * Note: Desktop does not support camera lens switching (single camera).
     */
    fun toggleCameraLens()

    /**
     * Gets the current camera lens.
     *
     * @return The current [CameraLens] (FRONT or BACK), or null if not available
     */
    fun getCameraLens(): CameraLens?

    /**
     * Gets the current image format.
     *
     * @return The configured [ImageFormat] (JPEG or PNG)
     */
    fun getImageFormat(): ImageFormat

    /**
     * Gets the current quality prioritization setting.
     *
     * @return The configured [QualityPrioritization]
     */
    fun getQualityPrioritization(): QualityPrioritization

    /**
     * Gets the current camera device type.
     *
     * @return The configured [CameraDeviceType]
     */
    fun getPreferredCameraDeviceType(): CameraDeviceType

    /**
     * Switches to a different camera device type at runtime.
     *
     * On iOS this switches between wide-angle, telephoto, ultra-wide, etc.
     * On Android this is a no-op (CameraX handles device selection automatically).
     * On Desktop this is a no-op (single camera).
     *
     * @param deviceType The desired [CameraDeviceType] to switch to.
     */
    fun setPreferredCameraDeviceType(deviceType: CameraDeviceType)

    /**
     * Sets the zoom level.
     *
     * @param zoomRatio The zoom ratio to set. 1.0 is no zoom, values > 1.0 zoom in.
     *                  The actual range depends on the camera hardware.
     *                  On Android: typically 1.0 to maxZoomRatio (often 2.0-10.0)
     *                  On iOS: typically 1.0 to device.maxAvailableVideoZoomFactor
     *                  On Desktop: not supported, no-op
     *
     * Note: Zoom is applied gradually/smoothly on supported platforms.
     */
    fun setZoom(zoomRatio: Float)

    /**
     * Gets the current zoom ratio.
     *
     * @return The current zoom ratio, or 1.0 if zoom is not supported
     */
    fun getZoom(): Float

    /**
     * Gets the maximum zoom ratio supported by the camera.
     *
     * @return The maximum zoom ratio, or 1.0 if zoom is not supported
     */
    fun getMaxZoom(): Float

    /**
     * Starts the camera session.
     */
    fun startSession()

    /**
     * Stops the camera session.
     */
    fun stopSession()

    /**
     * Adds a listener for image capture events.
     *
     * @param listener The listener to add, receiving image data as [ByteArray].
     */
    fun addImageCaptureListener(listener: (ByteArray) -> Unit)

    /**
     * Initializes all registered plugins.
     */
    fun initializeControllerPlugins()

    /**
     * Cleans up resources when the controller is no longer needed.
     * Should be called when disposing the controller to prevent memory leaks.
     *
     * Note: After calling cleanup(), the controller should not be used again.
     */
    fun cleanup()

    // ═══════════════════════════════════════════════════════════════
    // Device Orientation
    // ═══════════════════════════════════════════════════════════════

    fun getDeviceOrientation(): DeviceOrientation

    fun setOnOrientationChangedListener(callback: ((DeviceOrientation) -> Unit)?)

    /**
     * Locks camera output orientation to a specific value.
     *
     * This affects image capture rotation and video recording rotation.
     * On iOS, also updates the preview layer's video orientation.
     *
     * Pass `null` to follow the device's physical orientation automatically (default).
     */
    fun setTargetOrientation(orientation: DeviceOrientation?)

    // ═══════════════════════════════════════════════════════════════
    // Video Recording
    // ═══════════════════════════════════════════════════════════════

    /**
     * Starts video recording to a file.
     *
     * Safe to call while photo capture is active — both use cases coexist.
     * The output file format is MP4 (H.264 video + AAC audio) on all platforms.
     *
     * @param configuration Recording settings (quality, audio, duration limit, output path).
     * @return The actual output file path where the recording is being written.
     */
    suspend fun startRecording(configuration: VideoConfiguration = VideoConfiguration()): String

    /**
     * Stops the active video recording and finalizes the output file.
     *
     * Suspends until the file is fully written and closed.
     *
     * @return [VideoCaptureResult.Success] with file path and duration, or [VideoCaptureResult.Error].
     */
    suspend fun stopRecording(): VideoCaptureResult

    /**
     * Pauses the active video recording.
     *
     * Audio and video capture are suspended; the output file remains open.
     * No-op if not currently recording.
     *
     * Note: Desktop implementation is best-effort (frame-drop based).
     */
    suspend fun pauseRecording()

    /**
     * Resumes a paused video recording.
     *
     * No-op if not currently paused.
     */
    suspend fun resumeRecording()
}

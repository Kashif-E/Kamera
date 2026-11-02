package com.kashif.cameraK.builder

import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode

/**
 * Builder interface for constructing a [CameraController] with customizable configuration.
 */
interface CameraControllerBuilder {
    fun setFlashMode(flashMode: FlashMode): CameraControllerBuilder

    fun setCameraLens(cameraLens: CameraLens): CameraControllerBuilder

    /**
     * Sets the camera device type (e.g., wide-angle, telephoto, ultra-wide).
     *
     * Note: Availability depends on device hardware. If the requested type is not available,
     * the platform will fall back to the default camera.
     *
     * @param deviceType The desired camera device type
     * @return The current instance of [CameraControllerBuilder]
     */
    fun setPreferredCameraDeviceType(deviceType: CameraDeviceType): CameraControllerBuilder

    fun setImageFormat(imageFormat: ImageFormat): CameraControllerBuilder

    fun setDirectory(directory: Directory): CameraControllerBuilder

    /**
     * Builds and returns a configured instance of [CameraController].
     *
     * @throws InvalidConfigurationException If mandatory parameters are missing or configurations are incompatible.
     * @return A fully configured [CameraController] instance.
     */
    fun build(): CameraController

    fun setTorchMode(torchMode: TorchMode): CameraControllerBuilder

    /**
     * Sets the quality prioritization for the captured image.
     */
    fun setQualityPrioritization(prioritization: QualityPrioritization): CameraControllerBuilder

    /**
     * Sets the aspect ratio for preview and capture.
     * Supported values map to platform defaults (16:9, 4:3). 9:16 uses 16:9 with rotation; 1:1 falls back to closest available.
     */
    fun setAspectRatio(aspectRatio: AspectRatio): CameraControllerBuilder

    /**
     * Sets a target capture resolution (width x height) for preview/capture when the platform supports it.
     * Platforms may fall back to the closest supported resolution if an exact match is unavailable.
     */
    fun setResolution(width: Int, height: Int): CameraControllerBuilder

    /**
     * When true, front-camera captures are horizontally mirrored to match the mirrored preview.
     * No effect on the back camera. Defaults to a no-op on platforms without a front/back lens.
     */
    fun setMirrorFrontCamera(mirror: Boolean): CameraControllerBuilder = this
}

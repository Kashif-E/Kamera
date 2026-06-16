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
import com.kashif.cameraK.plugins.CameraPlugin
import com.kashif.cameraK.utils.InvalidConfigurationException

/**
 * iOS-specific implementation of [CameraControllerBuilder].
 */
class IOSCameraControllerBuilder : CameraControllerBuilder {

    private var flashMode: FlashMode = FlashMode.OFF
    private var torchMode: TorchMode = TorchMode.OFF
    private var cameraLens: CameraLens = CameraLens.BACK
    private var imageFormat: ImageFormat? = null
    private var directory: Directory? = null
    private var qualityPriority: QualityPrioritization = QualityPrioritization.NONE
    private var cameraDeviceType: CameraDeviceType = CameraDeviceType.DEFAULT
    private var returnFilePath: Boolean = false
    private var aspectRatio: AspectRatio = AspectRatio.RATIO_4_3
    private var targetResolution: Pair<Int, Int>? = null
    private var mirrorFrontCamera: Boolean = false
    private val plugins = mutableListOf<CameraPlugin>()

    override fun setMirrorFrontCamera(mirror: Boolean): CameraControllerBuilder {
        this.mirrorFrontCamera = mirror
        return this
    }

    override fun setFlashMode(flashMode: FlashMode): CameraControllerBuilder {
        this.flashMode = flashMode
        return this
    }

    override fun setCameraLens(cameraLens: CameraLens): CameraControllerBuilder {
        this.cameraLens = cameraLens
        return this
    }

    override fun setImageFormat(imageFormat: ImageFormat): CameraControllerBuilder {
        this.imageFormat = imageFormat
        return this
    }

    override fun setTorchMode(torchMode: TorchMode): CameraControllerBuilder {
        this.torchMode = torchMode
        return this
    }

    override fun setQualityPrioritization(prioritization: QualityPrioritization): CameraControllerBuilder {
        this.qualityPriority = prioritization
        return this
    }

    override fun setReturnFilePath(returnFilePath: Boolean): CameraControllerBuilder {
        this.returnFilePath = returnFilePath
        return this
    }

    override fun setAspectRatio(aspectRatio: AspectRatio): CameraControllerBuilder {
        this.aspectRatio = aspectRatio
        return this
    }

    override fun setResolution(width: Int, height: Int): CameraControllerBuilder {
        this.targetResolution = width to height
        return this
    }

    override fun setDirectory(directory: Directory): CameraControllerBuilder {
        this.directory = directory
        return this
    }

    /**
     * Sets the preferred camera device type (wide-angle, telephoto, etc.).
     * Note: Availability depends on device hardware.
     *
     * @param deviceType The desired camera device type.
     * @return This builder instance for chaining.
     */
    override fun setPreferredCameraDeviceType(deviceType: CameraDeviceType): CameraControllerBuilder {
        this.cameraDeviceType = deviceType
        return this
    }

    override fun addPlugin(plugin: CameraPlugin): CameraControllerBuilder {
        plugins.add(plugin)
        return this
    }

    /**
     * Builds the configured iOS camera controller.
     *
     * @return A fully configured [CameraController] instance.
     * @throws InvalidConfigurationException If required configuration is missing.
     */
    override fun build(): CameraController {
        val format = imageFormat ?: throw InvalidConfigurationException("ImageFormat must be set.")
        val dir = directory ?: throw InvalidConfigurationException("Directory must be set.")

        val cameraController = CameraController(
            flashMode = flashMode,
            torchMode = torchMode,
            cameraLens = cameraLens,
            imageFormat = format,
            directory = dir,
            plugins = plugins,
            qualityPriority = qualityPriority,
            cameraDeviceType = cameraDeviceType,
            returnFilePath = returnFilePath,
            aspectRatio = aspectRatio,
            targetResolution = targetResolution,
            mirrorFrontCamera = mirrorFrontCamera,
        )

        return cameraController
    }
}

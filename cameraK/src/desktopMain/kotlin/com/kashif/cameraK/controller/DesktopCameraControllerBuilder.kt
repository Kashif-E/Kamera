package com.kashif.cameraK.controller

import com.kashif.cameraK.builder.CameraControllerBuilder
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.utils.InvalidConfigurationException
import org.bytedeco.javacv.FrameGrabber

/**
 * Desktop-specific implementation of [CameraControllerBuilder] using JavaCV/FFmpeg.
 * Supports camera capture on JVM-based desktop environments.
 */
class DesktopCameraControllerBuilder : CameraControllerBuilder {
    private var grabber: FrameGrabber? = null
    private var horizontalFlip: Boolean = false
    private var flashMode: FlashMode = FlashMode.OFF
    private var torchMode: TorchMode = TorchMode.OFF
    private var cameraLens: CameraLens = CameraLens.BACK
    private var imageFormat: ImageFormat? = null
    private var directory: Directory? = null
    private var qualityPriority: QualityPrioritization = QualityPrioritization.NONE
    private var targetResolution: Pair<Int, Int>? = null

    /**
     * Sets the frame grabber for camera input.
     *
     * @param grabber The JavaCV FrameGrabber instance.
     * @return This builder instance for chaining.
     */
    fun setGrabber(grabber: FrameGrabber): CameraControllerBuilder {
        this.grabber = grabber
        return this
    }

    /**
     * Configures whether to flip frames horizontally.
     *
     * @param horizontalFlip True to flip frames horizontally.
     * @return This builder instance for chaining.
     */
    fun setHorizontalFlip(horizontalFlip: Boolean): CameraControllerBuilder {
        this.horizontalFlip = horizontalFlip
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

    override fun setPreferredCameraDeviceType(deviceType: CameraDeviceType): CameraControllerBuilder = this

    override fun setImageFormat(imageFormat: ImageFormat): CameraControllerBuilder {
        this.imageFormat = imageFormat
        return this
    }

    override fun setTorchMode(torchMode: TorchMode): CameraControllerBuilder {
        this.torchMode = torchMode
        return this
    }

    override fun setResolution(width: Int, height: Int): CameraControllerBuilder {
        targetResolution = width to height
        return this
    }

    override fun setQualityPrioritization(prioritization: QualityPrioritization): CameraControllerBuilder {
        this.qualityPriority = prioritization
        return this
    }

    override fun setAspectRatio(aspectRatio: AspectRatio): CameraControllerBuilder = this

    override fun setDirectory(directory: Directory): CameraControllerBuilder {
        this.directory = directory
        return this
    }

    override fun build(): CameraController {
        val format = imageFormat ?: throw InvalidConfigurationException("ImageFormat must be set.")
        val dir = directory ?: throw InvalidConfigurationException("Directory must be set.")

        return CameraController(
            imageFormat = format,
            directory = dir,
            horizontalFlip = horizontalFlip,
            customGrabber = grabber,
            targetResolution = targetResolution,
        )
    }
}

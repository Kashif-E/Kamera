package com.kashif.cameraK.controller
import com.kashif.cameraK.utils.CameraKLogger

import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.GraphicsEnvironment
import java.awt.Transparency
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage

class FrameConverter {
    private val converter = Java2DFrameConverter()
    private var cachedImage: BufferedImage? = null
    private val graphicsConfig =
        GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .defaultScreenDevice
            .defaultConfiguration

    private var horizontalFlip = false

    fun setHorizontalFlip(flip: Boolean) {
        this.horizontalFlip = flip
    }

    fun convert(frame: Frame): BufferedImage? {
        return try {
            // Convert the frame to a BufferedImage
            val originalImage = converter.convert(frame)

            // If flipping is not needed or image is null, return the original
            if (!horizontalFlip || originalImage == null) {
                return originalImage
            }

            // Create a compatible image for flipping
            if (cachedImage == null ||
                cachedImage?.width != originalImage.width ||
                cachedImage?.height != originalImage.height
            ) {
                cachedImage =
                    graphicsConfig.createCompatibleImage(
                        originalImage.width,
                        originalImage.height,
                        Transparency.TRANSLUCENT,
                    )
            }

            val graphics = cachedImage!!.createGraphics()
            val tx = AffineTransform()
            tx.translate(originalImage.width.toDouble(), 0.0)
            tx.scale(-1.0, 1.0)
            graphics.transform = tx
            graphics.drawImage(originalImage, 0, 0, null)
            graphics.dispose()
            cachedImage
        } catch (e: Exception) {
            CameraKLogger.e("CameraK", "error", e)
            // On error return the original frame
            converter.convert(frame)
        }
    }

    fun release() {
        converter.close()
        cachedImage = null
    }
}

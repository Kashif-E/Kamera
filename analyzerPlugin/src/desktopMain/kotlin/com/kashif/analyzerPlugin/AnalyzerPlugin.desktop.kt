package com.kashif.analyzerPlugin

import com.kashif.cameraK.frames.CameraFrame
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.ImageWriter

internal actual fun platformFrameEncoder(config: AnalyzerConfig): FrameEncoder = ImageIoFrameEncoder(config)

/**
 * Encodes through a BufferedImage and ImageIO's JPEG writer, from the frame's copied planes. The
 * frame-sized and encoded-sized images are reused while frames keep their size; the JPEG bytes are
 * new for every frame.
 */
internal class ImageIoFrameEncoder(private val config: AnalyzerConfig) : FrameEncoder {
    private val reader = RgbFrameReader()
    private var full: BufferedImage? = null
    private var scaled: BufferedImage? = null

    override fun encode(frame: CameraFrame): EncodedFrame? {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull() ?: return null
        try {
            val image = fullImage(frame.width, frame.height)
            val pixels = reader.read(frame)
            pixels.copyInto((image.raster.dataBuffer as DataBufferInt).data)
            val (width, height) = encodedSize(frame.width, frame.height, config.maxDimension)
            val encoded = if (width == frame.width && height == frame.height) image else scale(image, width, height)
            val bytes = write(writer, encoded)
            return EncodedFrame(bytes, width, height, frame.rotationDegrees, frame.timestampNanos)
        } finally {
            writer.dispose()
        }
    }

    private fun fullImage(width: Int, height: Int): BufferedImage =
        full?.takeIf { it.width == width && it.height == height }
            ?: BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { full = it }

    private fun scale(source: BufferedImage, width: Int, height: Int): BufferedImage {
        val target = scaled?.takeIf { it.width == width && it.height == height }
            ?: BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { scaled = it }
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    private fun write(writer: ImageWriter, image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream(image.width * image.height / JPEG_SIZE_GUESS_DIVISOR)
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = config.jpegQuality / MAX_QUALITY
            }
            writer.write(null, IIOImage(image, null, null), param)
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_QUALITY = 100f
        const val JPEG_SIZE_GUESS_DIVISOR = 4
    }
}

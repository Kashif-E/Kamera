package com.kashif.qrscannerplugin

import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.client.j2se.MatrixToImageConfig
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.kashif.cameraK.frames.FrameRect
import com.kashif.cameraK.testing.TestFrame
import java.awt.Color
import java.awt.image.BufferedImage
import com.google.zxing.BarcodeFormat as ZxingFormat

/**
 * A [width] x [height] scene with a code for [text] at ([left], [top]), or centred when they are
 * null, drawn in [dark] on [light]. A QR code or Data Matrix is three quarters of the shorter side; a 1D barcode
 * is three quarters of the width and a third of the height.
 */
internal fun codeScene(
    text: String,
    width: Int,
    height: Int,
    format: ZxingFormat = ZxingFormat.QR_CODE,
    dark: Int = 0x000000,
    light: Int = 0xFFFFFF,
    left: Int? = null,
    top: Int? = null,
): BufferedImage {
    val (codeWidth, codeHeight) = when (format) {
        ZxingFormat.QR_CODE, ZxingFormat.DATA_MATRIX -> (minOf(width, height) * 3 / 4).let { it to it }
        else -> width * 3 / 4 to height / 3
    }
    val matrix = MultiFormatWriter().encode(
        text,
        format,
        codeWidth,
        codeHeight,
        mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 0),
    )
    val code = MatrixToImageWriter.toBufferedImage(matrix, MatrixToImageConfig(OPAQUE or dark, OPAQUE or light))
    val scene = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    scene.createGraphics().apply {
        color = Color(light)
        fillRect(0, 0, width, height)
        drawImage(code, left ?: ((width - code.width) / 2), top ?: ((height - code.height) / 2), null)
        dispose()
    }
    return scene
}

/** [this] turned 90 degrees anticlockwise: a frame that needs 90 degrees clockwise to be upright again. */
internal fun BufferedImage.turnedAnticlockwise(): BufferedImage {
    val turned = BufferedImage(height, width, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until height) for (x in 0 until width) turned.setRGB(y, width - 1 - x, getRGB(x, y))
    return turned
}

/** [this]'s BT.601 luma, as a camera's YUV_420 Y plane holds it, row after row. */
internal fun BufferedImage.luma(): ByteArray = ByteArray(width * height) { i ->
    val pixel = getRGB(i % width, i / width)
    ((299 * (pixel shr 16 and 0xFF) + 587 * (pixel shr 8 and 0xFF) + 114 * (pixel and 0xFF) + 500) / 1000).toByte()
}

/** [this] as a YUV_420 test frame. */
internal fun BufferedImage.yuvFrame(
    rotationDegrees: Int = 0,
    timestampNanos: Long = 0,
    cropRect: FrameRect? = null,
): TestFrame = TestFrame.luma(
    width,
    height,
    luma(),
    rotationDegrees = rotationDegrees,
    timestampNanos = timestampNanos,
    cropRect = cropRect,
)

/** [this] as an RGBA_8888 test frame. */
internal fun BufferedImage.rgbaFrame(timestampNanos: Long = 0): TestFrame {
    val bytes = ByteArray(width * height * 4)
    for (i in 0 until width * height) {
        val pixel = getRGB(i % width, i / width)
        bytes[i * 4] = (pixel shr 16).toByte()
        bytes[i * 4 + 1] = (pixel shr 8).toByte()
        bytes[i * 4 + 2] = pixel.toByte()
        bytes[i * 4 + 3] = -1
    }
    return TestFrame.rgba(width, height, bytes, timestampNanos = timestampNanos)
}

/** [this] as the plugin hands it to a decoder: the whole frame, or [area], turned by [rotationDegrees]. */
internal fun BufferedImage.lumaImage(
    area: FrameRect = FrameRect(0, 0, width, height),
    rotationDegrees: Int = 0,
): LumaImage = LumaImage(luma(), width, height, area, rotationDegrees)

private const val OPAQUE = -0x1000000

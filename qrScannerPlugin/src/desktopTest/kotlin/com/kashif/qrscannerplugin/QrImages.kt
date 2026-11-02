package com.kashif.qrscannerplugin

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.Color
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicInteger

/** A [width] x [height] BGR frame with a QR code for [text] in the middle, on white. */
internal fun qrImage(text: String, width: Int, height: Int): BufferedImage {
    val side = minOf(width, height) * 3 / 4
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, side, side)
    val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
    image.createGraphics().apply {
        color = Color.WHITE
        fillRect(0, 0, width, height)
        dispose()
    }
    val left = (width - matrix.width) / 2
    val top = (height - matrix.height) / 2
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            if (matrix[x, y]) image.setRGB(left + x, top + y, 0x000000)
        }
    }
    return image
}

/**
 * A camera that delivers [image] every [FRAME_INTERVAL_MS], as a webcam's grab() blocks until its
 * next frame, and calls [onGrab] with the number of frames delivered so far.
 */
internal class StillCamera(image: BufferedImage, private val onGrab: (Int) -> Unit = {}) : FrameGrabber() {
    private val frame: Frame = Java2DFrameConverter().convert(image)
    private val delivered = AtomicInteger()

    val grabs: Int get() = delivered.get()

    override fun start() = Unit

    override fun stop() = Unit

    override fun trigger() = Unit

    override fun grab(): Frame {
        Thread.sleep(FRAME_INTERVAL_MS)
        onGrab(delivered.incrementAndGet())
        return frame
    }

    override fun release() = Unit

    companion object {
        const val FRAME_INTERVAL_MS = 10L
    }
}

package com.kashif.cameraK.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.impl.BufferUtil
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.PixelInterleavedSampleModel
import java.nio.ByteOrder
import java.nio.IntBuffer

/**
 * Turns camera frames into preview bitmaps without allocating per frame: two Skia bitmaps, sized to
 * the stream, are written in turn (a double buffer). [render] runs on one background thread and
 * writes the back bitmap, then swaps it to the front; [drawLatest] runs on the UI thread and reads
 * the front bitmap under the same lock, so a bitmap is never written while it is being drawn.
 */
internal class PreviewFrameRenderer {
    private val lock = Any()
    private var front: Buffer? = null
    private var back: Buffer? = null
    private var closed = false
    private var pixels = IntArray(0)

    /**
     * Writes [image] into the back bitmap, makes it the front one and returns it. Called from one
     * thread at a time, never after [close].
     */
    fun render(image: BufferedImage): ImageBitmap {
        val width = image.width
        val height = image.height
        if (pixels.size != width * height) pixels = IntArray(width * height)
        readArgb(image, pixels)

        val target = synchronized(lock) {
            check(!closed) { "The preview renderer is closed" }
            back?.takeIf { it.width == width && it.height == height }
                ?: Buffer(width, height).also {
                    back?.close()
                    back = it
                }
        }
        target.write(pixels)
        synchronized(lock) {
            check(!closed) { "The preview renderer is closed" }
            back = front
            front = target
        }
        return target.imageBitmap
    }

    /** Runs [draw] with the latest frame's bitmap, if there is one, holding it still while it draws. */
    fun drawLatest(draw: (ImageBitmap) -> Unit) {
        synchronized(lock) { front?.let { draw(it.imageBitmap) } }
    }

    /** Frees both bitmaps. Later [render] and [drawLatest] calls do nothing. */
    fun close() = synchronized(lock) {
        closed = true
        front?.close()
        back?.close()
        front = null
        back = null
    }

    private class Buffer(val width: Int, val height: Int) {
        private val bitmap = Bitmap().also {
            val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE, ColorSpace.sRGB)
            check(it.allocPixels(info)) { "Could not allocate a ${width}x$height preview bitmap" }
        }

        // BGRA bytes read as one little-endian int are 0xAARRGGBB, the layout of an AWT ARGB pixel,
        // so a row of ARGB ints copies straight into the bitmap's pixel memory.
        private val rowBytes: Int
        private val target: IntBuffer

        init {
            val pixmap = checkNotNull(bitmap.peekPixels()) { "The preview bitmap has no pixel memory" }
            try {
                rowBytes = pixmap.rowBytes
                target = BufferUtil.getByteBufferFromPointer(pixmap.addr, rowBytes * height)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asIntBuffer()
            } finally {
                pixmap.close()
            }
        }

        val imageBitmap: ImageBitmap = bitmap.asComposeImageBitmap()

        fun write(argb: IntArray) {
            if (rowBytes == width * 4) {
                target.put(0, argb, 0, width * height)
            } else {
                for (y in 0 until height) target.put(y * rowBytes / 4, argb, y * width, width)
            }
            bitmap.notifyPixelsChanged()
        }

        fun close() = bitmap.close()
    }
}

/** Reads [image] as ARGB ints into [out], without allocating, straight from the bytes of a BGR frame. */
private fun readArgb(image: BufferedImage, out: IntArray) {
    val width = image.width
    val height = image.height
    val raster = image.raster
    val layout = raster.sampleModel as? PixelInterleavedSampleModel
    val bytes = raster.dataBuffer as? DataBufferByte
    if (image.type != BufferedImage.TYPE_3BYTE_BGR || layout == null || bytes == null || raster.parent != null) {
        image.getRGB(0, 0, width, height, out, 0, width)
        return
    }
    val data = bytes.data
    val stride = layout.scanlineStride
    val pixelStride = layout.pixelStride
    val (red, green, blue) = layout.bandOffsets
    var row = bytes.offset
    var i = 0
    for (y in 0 until height) {
        var p = row
        for (x in 0 until width) {
            out[i++] = OPAQUE or
                ((data[p + red].toInt() and 0xFF) shl 16) or
                ((data[p + green].toInt() and 0xFF) shl 8) or
                (data[p + blue].toInt() and 0xFF)
            p += pixelStride
        }
        row += stride
    }
}

private const val OPAQUE = -0x1000000

package com.kashif.qrscannerplugin

import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import java.awt.image.BufferedImage
import java.awt.image.ComponentSampleModel
import java.awt.image.DataBufferByte

/**
 * Converts frames to the 8-bit luminance ZXing decodes, in one buffer reused while frames keep their
 * size. A frame whose longer side exceeds [maxSide] is box-averaged down by a whole factor first.
 *
 * Not thread-safe, and the returned source reads the shared buffer: decode it before the next call.
 */
internal class GrayFrame(private val maxSide: Int = MAX_DECODE_SIDE) {
    private var luma = ByteArray(0)
    private var row = IntArray(0)
    private var sums = IntArray(0)

    fun luminance(image: BufferedImage): LuminanceSource {
        val scale = (maxOf(image.width, image.height) + maxSide - 1) / maxSide
        val width = image.width / scale
        val height = image.height / scale
        if (luma.size != width * height) luma = ByteArray(width * height)
        if (row.size < image.width) row = IntArray(image.width)
        if (sums.size < width) sums = IntArray(width)

        val area = scale * scale
        for (y in 0 until height) {
            sums.fill(0, 0, width)
            for (dy in 0 until scale) {
                readLumaRow(image, y * scale + dy)
                var sx = 0
                for (x in 0 until width) {
                    var sum = 0
                    repeat(scale) { sum += row[sx++] }
                    sums[x] += sum
                }
            }
            val offset = y * width
            for (x in 0 until width) luma[offset + x] = (sums[x] / area).toByte()
        }
        return PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)
    }

    /** Fills [row] with the luma of each pixel of row [y], weighted as ZXing's own sources weight it. */
    private fun readLumaRow(image: BufferedImage, y: Int) {
        val width = image.width
        val raster = image.raster
        val model = raster.sampleModel
        when {
            image.type == BufferedImage.TYPE_BYTE_GRAY -> raster.getSamples(0, y, width, 1, 0, row)

            // What the JavaCV frame converter produces: read the bytes, without a colour model per pixel.
            image.type == BufferedImage.TYPE_3BYTE_BGR && model is ComponentSampleModel -> {
                val data = (raster.dataBuffer as DataBufferByte).data
                val bands = model.bandOffsets
                val start = (y - raster.sampleModelTranslateY) * model.scanlineStride -
                    raster.sampleModelTranslateX * model.pixelStride
                for (x in 0 until width) {
                    val i = start + x * model.pixelStride
                    row[x] = luma(
                        data[i + bands[0]].toInt() and 0xFF,
                        data[i + bands[1]].toInt() and 0xFF,
                        data[i + bands[2]].toInt() and 0xFF,
                    )
                }
            }

            else -> {
                image.getRGB(0, y, width, 1, row, 0, width)
                for (x in 0 until width) {
                    val pixel = row[x]
                    // A fully transparent pixel reads as white, as in ZXing's BufferedImageLuminanceSource.
                    row[x] =
                        if (pixel ushr 24 ==
                            0
                        ) {
                            0xFF
                        } else {
                            luma(pixel shr 16 and 0xFF, pixel shr 8 and 0xFF, pixel and 0xFF)
                        }
                }
            }
        }
    }

    private fun luma(r: Int, g: Int, b: Int): Int = (306 * r + 601 * g + 117 * b + 0x200) shr 10

    private companion object {
        // 720p decodes at full size; 1080p and larger are averaged down, which keeps a code's modules
        // several pixels wide at a typical scanning distance.
        const val MAX_DECODE_SIDE = 1280
    }
}

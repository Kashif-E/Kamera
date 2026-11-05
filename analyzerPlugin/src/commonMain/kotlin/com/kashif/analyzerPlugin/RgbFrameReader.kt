package com.kashif.analyzerPlugin

import com.kashif.cameraK.frames.CameraFrame
import com.kashif.cameraK.frames.FrameFormat

/**
 * Reads a frame's colour into one ARGB int per pixel, row by row without padding. YUV_420 is read
 * as full-range BT.601 from its copied planes, whether [Y, U, V] (any pixel stride) or [Y, CbCr]
 * interleaved; a luma-only frame reads as grey. RGBA_8888 is read from its one plane. The plane
 * buffers and the pixel array are reused while frames keep their size.
 */
internal class RgbFrameReader {
    private val planeCopies = arrayOfNulls<ByteArray>(MAX_PLANES)
    private var pixels = IntArray(0)

    /** [frame]'s pixels; the array is this reader's and is overwritten by the next call. */
    fun read(frame: CameraFrame): IntArray {
        val count = frame.width * frame.height
        if (pixels.size != count) pixels = IntArray(count)
        when (frame.format) {
            FrameFormat.RGBA_8888 -> readRgba(frame)
            FrameFormat.YUV_420 -> readYuv(frame)
        }
        return pixels
    }

    private fun copyOf(frame: CameraFrame, index: Int): ByteArray {
        val plane = frame.planes[index]
        val copy = planeCopies[index]?.takeIf { it.size >= plane.size } ?: ByteArray(plane.size).also {
            planeCopies[index] = it
        }
        plane.copyTo(copy)
        return copy
    }

    private fun readRgba(frame: CameraFrame) {
        val rgba = copyOf(frame, 0)
        val stride = frame.planes[0].rowStride
        var out = 0
        for (y in 0 until frame.height) {
            var at = y * stride
            for (x in 0 until frame.width) {
                pixels[out++] =
                    argb(rgba[at].toInt() and 0xFF, rgba[at + 1].toInt() and 0xFF, rgba[at + 2].toInt() and 0xFF)
                at += RGBA_BYTES_PER_PIXEL
            }
        }
    }

    private fun readYuv(frame: CameraFrame) {
        val planes = frame.planes
        val luma = copyOf(frame, 0)
        val lumaStride = planes[0].rowStride
        if (planes.size == 1) {
            readGrey(frame, luma, lumaStride)
            return
        }
        // [Y, CbCr]: one interleaved plane, Cb first. [Y, U, V]: each chroma plane on its own.
        val interleaved = planes.size == 2
        val u = Chroma(copyOf(frame, 1), planes[1].rowStride, planes[1].pixelStride, offset = 0)
        val v = if (interleaved) {
            Chroma(u.bytes, u.rowStride, u.pixelStride, offset = 1)
        } else {
            Chroma(copyOf(frame, 2), planes[2].rowStride, planes[2].pixelStride, offset = 0)
        }
        var out = 0
        for (y in 0 until frame.height) {
            val lumaRow = y * lumaStride
            val uRow = (y / 2) * u.rowStride + u.offset
            val vRow = (y / 2) * v.rowStride + v.offset
            for (x in 0 until frame.width) {
                val l = luma[lumaRow + x].toInt() and 0xFF
                val cb = (u.bytes[uRow + (x / 2) * u.pixelStride].toInt() and 0xFF) - CHROMA_ZERO
                val cr = (v.bytes[vRow + (x / 2) * v.pixelStride].toInt() and 0xFF) - CHROMA_ZERO
                val base = (l shl FIXED_POINT_BITS) + ROUNDING
                pixels[out++] = argb(
                    channel(base + CR_TO_R * cr),
                    channel(base - CB_TO_G * cb - CR_TO_G * cr),
                    channel(base + CB_TO_B * cb),
                )
            }
        }
    }

    private fun readGrey(frame: CameraFrame, luma: ByteArray, stride: Int) {
        var out = 0
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val l = luma[y * stride + x].toInt() and 0xFF
                pixels[out++] = argb(l, l, l)
            }
        }
    }

    private class Chroma(val bytes: ByteArray, val rowStride: Int, val pixelStride: Int, val offset: Int)

    private companion object {
        // Plane 0 and up to two chroma planes.
        const val MAX_PLANES = 3
        const val RGBA_BYTES_PER_PIXEL = 4
        const val CHROMA_ZERO = 128

        // Full-range BT.601 (JFIF), in 16-bit fixed point: R = Y + 1.402 Cr, G = Y - 0.344136 Cb
        // - 0.714136 Cr, B = Y + 1.772 Cb. ROUNDING rounds to nearest.
        const val FIXED_POINT_BITS = 16
        const val ROUNDING = 1 shl (FIXED_POINT_BITS - 1)
        const val CR_TO_R = 91881
        const val CB_TO_G = 22554
        const val CR_TO_G = 46802
        const val CB_TO_B = 116130

        fun channel(fixed: Int): Int = (fixed shr FIXED_POINT_BITS).coerceIn(0, 255)

        fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

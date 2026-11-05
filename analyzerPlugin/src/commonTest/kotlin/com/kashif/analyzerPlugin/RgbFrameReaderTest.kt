package com.kashif.analyzerPlugin

import com.kashif.cameraK.frames.CameraFrame
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FramePlane
import com.kashif.cameraK.frames.FrameRect
import com.kashif.cameraK.frames.RetainedFrame
import com.kashif.cameraK.testing.TestFrame
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The analyzer reads colour from each platform's plane layout: I420 (desktop, TestFrame), three
 * planes with pixel stride 2 (Android's YUV_420_888), [Y, CbCr] (iOS 420f), and RGBA_8888.
 */
class RgbFrameReaderTest {
    // A 4 × 2 frame: two 2 × 2 chroma blocks, a red one on the left and a blue one on the right.
    private val luma = intArrayOf(76, 90, 30, 40, 80, 100, 35, 45)
    private val blocks = listOf(85 to 255, 255 to 107)

    @Test
    fun i420PlanesReadAsTheirColours() {
        val frame = PlanesFrame(
            FrameFormat.YUV_420,
            listOf(
                Plane(lumaRows(rowStride = 4), rowStride = 4, pixelStride = 1),
                Plane(bytes(blocks[0].first, blocks[1].first), rowStride = 2, pixelStride = 1),
                Plane(bytes(blocks[0].second, blocks[1].second), rowStride = 2, pixelStride = 1),
            ),
        )

        assertColours(RgbFrameReader().read(frame))
    }

    @Test
    fun threePlanesWithPixelStrideTwoAndRowPaddingReadAsTheirColours() {
        // YUV_420_888 as Android delivers NV21: U and V interleaved, each plane read with stride 2.
        val (u0, v0) = blocks[0]
        val (u1, v1) = blocks[1]
        val frame = PlanesFrame(
            FrameFormat.YUV_420,
            listOf(
                Plane(lumaRows(rowStride = 8), rowStride = 8, pixelStride = 1),
                Plane(bytes(u0, v0, u1, 0, 0, 0, 0, 0), rowStride = 8, pixelStride = 2),
                Plane(bytes(v0, u1, v1, 0, 0, 0, 0, 0), rowStride = 8, pixelStride = 2),
            ),
        )

        assertColours(RgbFrameReader().read(frame))
    }

    @Test
    fun yAndInterleavedCbCrReadAsTheirColours() {
        val (u0, v0) = blocks[0]
        val (u1, v1) = blocks[1]
        val frame = PlanesFrame(
            FrameFormat.YUV_420,
            listOf(
                Plane(lumaRows(rowStride = 16), rowStride = 16, pixelStride = 1),
                Plane(bytes(u0, v0, u1, v1, 9, 9, 9, 9), rowStride = 8, pixelStride = 2),
            ),
        )

        assertColours(RgbFrameReader().read(frame))
    }

    @Test
    fun aLumaOnlyFrameReadsAsGrey() {
        val frame =
            PlanesFrame(FrameFormat.YUV_420, listOf(Plane(lumaRows(rowStride = 4), rowStride = 4, pixelStride = 1)))

        val pixels = RgbFrameReader().read(frame)

        assertEquals(luma.map { argb(it, it, it) }, pixels.toList())
    }

    @Test
    fun neutralChromaReadsAsGrey() {
        val frame = TestFrame.luma(4, 2, ByteArray(8) { luma[it].toByte() })

        assertEquals(luma.map { argb(it, it, it) }, RgbFrameReader().read(frame).toList())
    }

    @Test
    fun rgbaWithRowPaddingReadsAsItsPixels() {
        val rowStride = 4 * 4 + 3
        val rgba = ByteArray(rowStride + 16)
        val expected = IntArray(8) { pixel ->
            val (r, g, b) = Triple(pixel * 30, 255 - pixel * 20, pixel * 7)
            val at = (pixel / 4) * rowStride + (pixel % 4) * 4
            rgba[at] = r.toByte()
            rgba[at + 1] = g.toByte()
            rgba[at + 2] = b.toByte()
            rgba[at + 3] = 0x40
            argb(r, g, b)
        }

        val pixels = RgbFrameReader().read(TestFrame.rgba(4, 2, rgba, rowStride = rowStride))

        assertEquals(expected.toList(), pixels.toList(), "alpha is dropped: JPEG has none")
    }

    @Test
    fun theReaderFollowsAChangeOfSize() {
        val reader = RgbFrameReader()
        reader.read(TestFrame.luma(4, 2, ByteArray(8)))

        val pixels = reader.read(TestFrame.luma(2, 2, ByteArray(4) { 50 }))

        assertEquals(List(4) { argb(50, 50, 50) }, pixels.toList())
    }

    private fun lumaRows(rowStride: Int): ByteArray {
        val bytes = ByteArray(rowStride * 2)
        for (row in 0 until 2) for (col in 0 until 4) bytes[row * rowStride + col] = luma[row * 4 + col].toByte()
        return bytes
    }

    // Full-range BT.601 in floating point. The reader computes it in fixed point, so a channel may
    // round one step apart from this; that is the only difference allowed.
    private fun assertColours(pixels: IntArray) {
        assertTrue(pixels.size >= 8, "the reader returned ${pixels.size} pixels")
        for (pixel in 0 until 8) {
            val (u, v) = blocks[(pixel % 4) / 2]
            val y = luma[pixel]
            val expected = intArrayOf(
                clamp(y + 1.402 * (v - 128)),
                clamp(y - 0.344136 * (u - 128) - 0.714136 * (v - 128)),
                clamp(y + 1.772 * (u - 128)),
            )
            val actual = pixels[pixel]
            assertEquals(0xFF, actual ushr 24, "pixel $pixel is opaque")
            val channels = intArrayOf((actual shr 16) and 0xFF, (actual shr 8) and 0xFF, actual and 0xFF)
            for (c in 0 until 3) {
                assertTrue(
                    abs(channels[c] - expected[c]) <= 1,
                    "pixel $pixel: got ${channels.toList()}, expected ${expected.toList()}",
                )
            }
        }
    }

    private fun clamp(value: Double): Int = value.roundToInt().coerceIn(0, 255)

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private class Plane(private val bytes: ByteArray, override val rowStride: Int, override val pixelStride: Int) :
        FramePlane {
        override val size: Int get() = bytes.size

        override fun copyTo(destination: ByteArray, destinationOffset: Int): Int {
            bytes.copyInto(destination, destinationOffset)
            return bytes.size
        }

        override fun copyTo(destination: ByteArray, destinationOffset: Int, sourceOffset: Int, length: Int) {
            bytes.copyInto(destination, destinationOffset, sourceOffset, sourceOffset + length)
        }
    }

    private class PlanesFrame(override val format: FrameFormat, override val planes: List<FramePlane>) : CameraFrame {
        override val width: Int get() = 4
        override val height: Int get() = 2
        override val rotationDegrees: Int get() = 0
        override val timestampNanos: Long get() = 0
        override val cropRect: FrameRect get() = FrameRect(0, 0, 4, 2)

        override fun retain(): RetainedFrame = error("the analyzer never retains a frame")
    }
}

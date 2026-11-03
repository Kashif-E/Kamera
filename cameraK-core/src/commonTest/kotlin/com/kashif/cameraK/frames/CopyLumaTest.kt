package com.kashif.cameraK.frames

import com.kashif.cameraK.testing.TestFrame
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Grayscale out of either session format, without row padding and, given a scratch row, without allocating. */
class CopyLumaTest {
    private val padding: Byte = 0xEE.toByte()

    /** A frame whose planes record every array they are asked to copy into. */
    private class RecordingFrame(frame: CameraFrame, pixelStride: Int? = null) : CameraFrame by frame {
        val written = mutableListOf<ByteArray>()

        override val planes: List<FramePlane> =
            frame.planes.map { plane ->
                object : FramePlane by plane {
                    override val pixelStride: Int = pixelStride ?: plane.pixelStride

                    override fun copyTo(destination: ByteArray, destinationOffset: Int): Int {
                        written += destination
                        return plane.copyTo(destination, destinationOffset)
                    }

                    override fun copyTo(
                        destination: ByteArray,
                        destinationOffset: Int,
                        sourceOffset: Int,
                        length: Int,
                    ) {
                        written += destination
                        plane.copyTo(destination, destinationOffset, sourceOffset, length)
                    }
                }
            }
    }

    /** Six pixels, 3 x 2, and the BT.601 luma of each: round(0.299 R + 0.587 G + 0.114 B). */
    private val rgbPixels = listOf(
        Triple(255, 0, 0) to 76,
        Triple(0, 255, 0) to 150,
        Triple(0, 0, 255) to 29,
        Triple(255, 255, 255) to 255,
        Triple(0, 0, 0) to 0,
        Triple(100, 150, 200) to 141,
    )

    /** [rgbPixels] as a 3 x 2 RGBA frame with four bytes of padding after each row. */
    private fun rgbaFrame(): TestFrame {
        val rowStride = 3 * 4 + 4
        val bytes = ByteArray(rowStride * 2) { padding }
        rgbPixels.forEachIndexed { index, (rgb, _) ->
            val at = (index / 3) * rowStride + (index % 3) * 4
            bytes[at] = rgb.first.toByte()
            bytes[at + 1] = rgb.second.toByte()
            bytes[at + 2] = rgb.third.toByte()
            bytes[at + 3] = 0xFF.toByte()
        }
        return TestFrame.rgba(3, 2, bytes, rowStride = rowStride)
    }

    private val rgbLuma: ByteArray = rgbPixels.map { it.second.toByte() }.toByteArray()

    @Test
    fun yuvCopiesPlaneZeroRowsWithoutRowPadding() {
        // 3 x 2 luma at rowStride 5: two bytes of padding after the first row, none after the last.
        val luma = byteArrayOf(1, 2, 3, padding, padding, 4, 5, 6)
        val destination = ByteArray(10)

        val written = TestFrame.luma(3, 2, luma, rowStride = 5).copyLuma(destination, destinationOffset = 2)

        assertEquals(6, written)
        assertContentEquals(byteArrayOf(0, 0, 1, 2, 3, 4, 5, 6, 0, 0), destination)
    }

    @Test
    fun rgbaComputesBt601Luma() {
        val destination = ByteArray(6)

        val written = rgbaFrame().copyLuma(destination, scratch = ByteArray(3 * 4))

        assertEquals(6, written)
        assertContentEquals(rgbLuma, destination)
    }

    @Test
    fun withAScratchTheDestinationAndTheScratchAreTheOnlyArraysWritten() {
        val destination = ByteArray(6)
        val scratch = ByteArray(3 * 4)
        val rgba = RecordingFrame(rgbaFrame())
        val yuv = RecordingFrame(TestFrame.luma(3, 2, ByteArray(6) { it.toByte() }))

        rgba.copyLuma(destination, scratch = scratch)
        assertTrue(
            rgba.written.isNotEmpty() &&
                rgba.written.all {
                    it === scratch
                },
            "RGBA rows go through the scratch only",
        )
        assertContentEquals(rgbLuma, destination)

        yuv.copyLuma(destination, scratch = scratch)
        assertTrue(
            yuv.written.isNotEmpty() &&
                yuv.written.all {
                    it === destination
                },
            "YUV rows go straight to the destination",
        )
        assertEquals(2, yuv.written.size, "one ranged copy per row")
    }

    @Test
    fun rgbaWithNoScratchOrATooSmallOneStillComputesLuma() {
        val small = ByteArray(3 * 4 - 1)
        for (scratch in listOf(null, small)) {
            val destination = ByteArray(6)
            val frame = RecordingFrame(rgbaFrame())

            assertEquals(6, frame.copyLuma(destination, scratch = scratch))

            assertContentEquals(rgbLuma, destination, "scratch of ${scratch?.size} bytes")
            assertTrue(
                frame.written.none {
                    it === small || it === destination
                },
                "a row was copied into a buffer too small for it",
            )
        }
    }

    @Test
    fun aDestinationTooSmallIsRefused() {
        val frame = TestFrame.luma(3, 2, ByteArray(6))

        assertFailsWith<IllegalArgumentException> { frame.copyLuma(ByteArray(5)) }
        assertFailsWith<IllegalArgumentException> { frame.copyLuma(ByteArray(6), destinationOffset = 1) }
        assertFailsWith<IllegalArgumentException> { frame.copyLuma(ByteArray(6), destinationOffset = -1) }
    }

    @Test
    fun aPlaneZeroLaidOutOtherwiseThanItsFormatSaysIsRefused() {
        val interleavedLuma = RecordingFrame(TestFrame.luma(3, 2, ByteArray(6)), pixelStride = 2)
        val threeByteRgb = RecordingFrame(rgbaFrame(), pixelStride = 3)

        assertFailsWith<IllegalArgumentException> { interleavedLuma.copyLuma(ByteArray(6)) }
        assertFailsWith<IllegalArgumentException> { threeByteRgb.copyLuma(ByteArray(6)) }
    }
}

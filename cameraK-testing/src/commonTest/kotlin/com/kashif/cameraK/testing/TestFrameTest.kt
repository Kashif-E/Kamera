package com.kashif.cameraK.testing

import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FramePlane
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Frames built from bytes, laid out as a platform lays them out. */
class TestFrameTest {
    private fun FramePlane.bytes(): ByteArray = ByteArray(size).also { copyTo(it) }

    @Test
    fun lumaFrameIsYuvWithItsBytesInPlaneZeroAndNeutralChroma() {
        val luma = ByteArray(4 * 3 + 3) { it.toByte() } // 5 bytes per row, the last row unpadded
        val frame = TestFrame.luma(4, 3, luma, rowStride = 5, rotationDegrees = 90, timestampNanos = 7)

        assertEquals(FrameFormat.YUV_420, frame.format)
        assertEquals(4 to 3, frame.width to frame.height)
        assertEquals(90, frame.rotationDegrees)
        assertEquals(7L, frame.timestampNanos)
        assertEquals(listOf(0, 0, 4, 3), frame.cropRect.let { listOf(it.left, it.top, it.right, it.bottom) })
        assertEquals(3, frame.planes.size, "Y, U and V")
        val y = frame.planes[0]
        assertEquals(5 to 1, y.rowStride to y.pixelStride)
        assertContentEquals(luma, y.bytes())
        for (chroma in frame.planes.drop(1)) {
            assertEquals(2 to 1, chroma.rowStride to chroma.pixelStride, "a chroma plane of a 4 x 3 frame")
            assertContentEquals(ByteArray(2 * 2) { 128.toByte() }, chroma.bytes())
        }
    }

    @Test
    fun rgbaFrameHasOneInterleavedPlane() {
        val pixels = ByteArray(2 * 2 * 4) { it.toByte() }
        val frame = TestFrame.rgba(2, 2, pixels)

        assertEquals(FrameFormat.RGBA_8888, frame.format)
        assertEquals(1, frame.planes.size)
        assertEquals(8 to 4, frame.planes[0].rowStride to frame.planes[0].pixelStride)
        assertContentEquals(pixels, frame.planes[0].bytes())
    }

    @Test
    fun rangedCopyCopiesOnlyTheRequestedBytes() {
        val frame = TestFrame.luma(4, 2, ByteArray(8) { (it + 1).toByte() })
        val destination = ByteArray(6)

        frame.planes[0].copyTo(destination, destinationOffset = 1, sourceOffset = 3, length = 4)

        assertContentEquals(byteArrayOf(0, 4, 5, 6, 7, 0), destination)
    }

    @Test
    fun theFrameKeepsItsOwnCopyOfTheBytes() {
        val luma = ByteArray(4) { 1 }
        val frame = TestFrame.luma(2, 2, luma)

        luma.fill(9)

        assertContentEquals(ByteArray(4) { 1 }, frame.planes[0].bytes())
    }

    @Test
    fun bytesShorterThanTheLayoutAreRefused() {
        assertFailsWith<IllegalArgumentException> { TestFrame.luma(4, 3, ByteArray(4 * 3 - 1)) }
        assertFailsWith<IllegalArgumentException> { TestFrame.luma(4, 3, ByteArray(12), rowStride = 3) }
        assertFailsWith<IllegalArgumentException> { TestFrame.rgba(2, 2, ByteArray(2 * 2 * 4 - 1)) }
        assertFailsWith<IllegalArgumentException> { TestFrame.rgba(0, 2, ByteArray(0)) }
    }

    @Test
    fun aRetainedTestFrameReadsTheSameBytesAndClosesIdempotently() {
        val frame = TestFrame.luma(2, 1, byteArrayOf(3, 4))

        val retained = frame.retain()
        retained.close()
        retained.close()

        assertEquals(FrameFormat.YUV_420, retained.format)
        assertContentEquals(byteArrayOf(3, 4), retained.planes[0].bytes())
    }
}

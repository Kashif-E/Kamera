package com.kashif.cameraK.controller

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The desktop `FramePlane` (§8): a plane over part of a pooled buffer. Its ranged `copyTo` copies
 * one row at a time, a padded plane without its padding, and never reads outside its own plane.
 */
class DesktopFramePlaneTest {
    @Test
    fun rangedCopyToCopiesOnePaddedRowWithoutItsPadding() {
        // A plane of three 4-byte rows, each padded to 6, starting 3 bytes into a shared buffer.
        val buffer = byteArrayOf(
            90, 91, 92,
            1, 2, 3, 4, -1, -1,
            5, 6, 7, 8, -1, -1,
            9, 10, 11, 12, -1, -1,
            93,
        )
        val sut = DesktopFramePlane(buffer, offset = 3, rowStride = 6, pixelStride = 1, size = 18)
        val destination = ByteArray(6) { 0x55 }

        sut.copyTo(destination, destinationOffset = 1, sourceOffset = 6, length = 4)

        assertContentEquals(byteArrayOf(0x55, 5, 6, 7, 8, 0x55), destination, "the second row, without its padding")

        val unpadded = ByteArray(12)
        for (row in 0 until 3) sut.copyTo(unpadded, row * 4, row * sut.rowStride, 4)
        assertContentEquals(ByteArray(12) { (it + 1).toByte() }, unpadded, "the plane's rows, one at a time")

        val whole = ByteArray(18)
        assertEquals(18, sut.copyTo(whole), "the bulk copy's byte count")
        assertContentEquals(buffer.copyOfRange(3, 21), whole, "the bulk copy")

        assertFailsWith<IllegalArgumentException>("a range past the plane's end") {
            sut.copyTo(destination, 0, sourceOffset = 16, length = 4)
        }
        assertFailsWith<IllegalArgumentException>("a negative source offset") {
            sut.copyTo(destination, 0, sourceOffset = -1, length = 2)
        }
    }
}

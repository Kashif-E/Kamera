package com.kashif.qrscannerplugin

import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import java.awt.image.BufferedImage
import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Desktop decodes from a gray buffer it reuses, not a new frame-sized luminance image per attempt,
 * and frames larger than 1280 px on a side are downscaled before ZXing sees them.
 */
class QrLuminanceTest {
    private val decoder = ZxingQrFrameDecoder()

    @Test
    fun sameSizeFrames_allocateNoFrameSizedBufferPerAttempt() {
        val frame = qrImage("HELLO", 1280, 720)
        repeat(3) { decoder.luminance(frame) }
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().id

        val before = threads.getThreadAllocatedBytes(thread)
        repeat(10) { decoder.luminance(frame) }
        val perAttempt = (threads.getThreadAllocatedBytes(thread) - before) / 10

        // A gray copy of the frame is 921,600 bytes; the luminance source object itself is tiny.
        assertTrue(
            perAttempt < 1280 * 720 / 16,
            "building the luminance of a 1280x720 frame allocated $perAttempt bytes",
        )
    }

    @Test
    fun aFrameLargerThan1280_isDownscaled() {
        val source = decoder.luminance(qrImage("HELLO", 1920, 1080))

        assertEquals(960 to 540, source.width to source.height)
    }

    @Test
    fun luma_matchesZxingsWeighting() {
        val random = Random(42)
        val frame = BufferedImage(64, 48, BufferedImage.TYPE_3BYTE_BGR)
        for (y in 0 until 48) for (x in 0 until 64) frame.setRGB(x, y, random.nextInt(0x1000000))

        assertContentEquals(BufferedImageLuminanceSource(frame).matrix, decoder.luminance(frame).matrix)
    }

    @Test
    fun codesInLargeAndSmallFrames_stillDecode() {
        for ((width, height) in listOf(640 to 480, 1280 to 720, 1920 to 1080)) {
            val frame = qrImage("HELLO", width, height)
            assertEquals("HELLO", decoder.decode(frame, tryHarder = false), "fast pass at ${width}x$height")
            assertEquals("HELLO", decoder.decode(frame, tryHarder = true), "slow pass at ${width}x$height")
        }
    }
}

package com.kashif.analyzerPlugin

import com.kashif.cameraK.testing.TestFrame
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The desktop encoder hands out an owned JPEG of each common frame, in either session format: never
 * the frame's planes, scaled down to maxDimension, at the configured quality.
 */
class DesktopAnalyzerFrameTest {
    @Test
    fun aYuvFrame_isEncodedAsAnOwnedJpegOfTheFrame() {
        val luma = ByteArray(64 * 48) { 0x70 }
        val frame = TestFrame.luma(64, 48, luma, rotationDegrees = 270, timestampNanos = 42L)

        val encoded =
            assertNotNull(ImageIoFrameEncoder(AnalyzerConfig()).encode(frame), "the YUV frame was not encoded")

        val image = decode(encoded.bytes)
        assertEquals(64 to 48, image.width to image.height)
        assertEquals(64 to 48, encoded.width to encoded.height)
        assertEquals(270, encoded.rotationDegrees, "the frame's rotation is reported, the pixels are not turned")
        assertEquals(42L, encoded.timestampNanos)
        assertGrey(image.getRGB(32, 24), 0x70)
    }

    @Test
    fun anRgbaFrame_isEncodedAsAnOwnedJpegOfTheFrame_inItsColours() {
        val rgba = ByteArray(64 * 48 * 4)
        for (pixel in 0 until 64 * 48) {
            rgba[pixel * 4] = 0xE0.toByte()
            rgba[pixel * 4 + 1] = 0x20
            rgba[pixel * 4 + 2] = 0x30
            rgba[pixel * 4 + 3] = 0xFF.toByte()
        }

        val encoded = assertNotNull(ImageIoFrameEncoder(AnalyzerConfig()).encode(TestFrame.rgba(64, 48, rgba)))

        val image = decode(encoded.bytes)
        assertEquals(64 to 48, image.width to image.height)
        val rgb = image.getRGB(32, 24)
        assertTrue(
            (rgb shr 16 and 0xFF) > 0xC0 && (rgb shr 8 and 0xFF) < 0x50,
            "not the frame's red: %06x".format(
                rgb and 0xFFFFFF,
            ),
        )
    }

    @Test
    fun aFrameLargerThanMaxDimension_isScaledDownKeepingItsAspectRatio() {
        val frame = TestFrame.luma(400, 300, ByteArray(400 * 300) { 0x50 })

        val encoded = assertNotNull(ImageIoFrameEncoder(AnalyzerConfig { maxDimension = 200 }).encode(frame))

        val image = decode(encoded.bytes)
        assertEquals(200 to 150, image.width to image.height)
        assertEquals(200 to 150, encoded.width to encoded.height)
    }

    @Test
    fun aSmallerFrame_isNotScaledUp_andNullMaxDimensionKeepsFullSize() {
        val frame = TestFrame.luma(300, 200, ByteArray(300 * 200))

        val kept =
            decode(assertNotNull(ImageIoFrameEncoder(AnalyzerConfig { maxDimension = 1280 }).encode(frame)).bytes)
        val full =
            decode(assertNotNull(ImageIoFrameEncoder(AnalyzerConfig { maxDimension = null }).encode(frame)).bytes)

        assertEquals(300 to 200, kept.width to kept.height)
        assertEquals(300 to 200, full.width to full.height)
    }

    @Test
    fun jpegQualityReachesTheEncoder() {
        val noise = java.util.Random(7)
        val frame = TestFrame.luma(256, 256, ByteArray(256 * 256).also(noise::nextBytes))

        val low = assertNotNull(ImageIoFrameEncoder(AnalyzerConfig { jpegQuality = 10 }).encode(frame)).bytes
        val high = assertNotNull(ImageIoFrameEncoder(AnalyzerConfig { jpegQuality = 95 }).encode(frame)).bytes

        assertTrue(low.size * 2 < high.size, "quality 10 gave ${low.size} bytes, quality 95 ${high.size}")
    }

    @Test
    fun eachFrameGetsItsOwnBytes() {
        val encoder = ImageIoFrameEncoder(AnalyzerConfig())

        val first = assertNotNull(encoder.encode(TestFrame.luma(32, 32, ByteArray(32 * 32) { 0x10 }))).bytes
        val copy = first.copyOf()
        encoder.encode(TestFrame.luma(32, 32, ByteArray(32 * 32) { 0x7F }))

        assertTrue(copy.contentEquals(first), "encoding the next frame changed the bytes handed out for the last")
    }

    private fun decode(bytes: ByteArray): BufferedImage =
        assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)), "the payload is not an encoded image")

    // JPEG is lossy: a flat grey comes back within a few steps of itself.
    private fun assertGrey(rgb: Int, level: Int) {
        for (shift in listOf(16, 8, 0)) {
            val channel = rgb shr shift and 0xFF
            assertTrue(
                kotlin.math.abs(channel - level) <= 4,
                "channel $channel is not grey $level: %06x".format(
                    rgb and 0xFFFFFF,
                ),
            )
        }
    }
}

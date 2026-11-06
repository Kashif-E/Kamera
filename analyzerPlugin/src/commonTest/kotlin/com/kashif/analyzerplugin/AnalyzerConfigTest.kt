package com.kashif.analyzerplugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyzerConfigTest {
    @Test
    fun defaultsAreOneFrameASecondAt1280AndQuality80() {
        val config = AnalyzerConfig()

        assertEquals(1f, config.maxFps)
        assertEquals(1280, config.maxDimension)
        assertEquals(80, config.jpegQuality)
    }

    @Test
    fun jpegQualityOutside1To100IsRejectedWhenBuilt() {
        for (quality in listOf(0, -1, 101)) {
            val error = assertFailsWith<IllegalArgumentException>("quality $quality") {
                AnalyzerConfig { jpegQuality = quality }
            }
            assertTrue("jpegQuality" in error.message.orEmpty(), "the message names the field: ${error.message}")
        }
        assertEquals(1, AnalyzerConfig { jpegQuality = 1 }.jpegQuality)
        assertEquals(100, AnalyzerConfig { jpegQuality = 100 }.jpegQuality)
    }

    @Test
    fun aMaxDimensionBelowOneIsRejectedAndNullMeansFullSize() {
        assertFailsWith<IllegalArgumentException> { AnalyzerConfig { maxDimension = 0 } }
        assertFailsWith<IllegalArgumentException> { AnalyzerConfig { maxDimension = -8 } }
        assertNull(AnalyzerConfig { maxDimension = null }.maxDimension)
        assertNull(AnalyzerConfig { maxDimension = null }.copy { jpegQuality = 50 }.maxDimension, "copy keeps null")
    }

    @Test
    fun aMaxFpsThatIsNotAPositiveNumberIsRejected() {
        for (fps in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("maxFps $fps") { AnalyzerConfig { maxFps = fps } }
        }
    }

    @Test
    fun configsCompareByValue() {
        val config = AnalyzerConfig { maxFps = 2f }

        assertEquals(config, AnalyzerConfig { maxFps = 2f })
        assertEquals(config.hashCode(), AnalyzerConfig { maxFps = 2f }.hashCode())
        assertNotEquals(config, config.copy { jpegQuality = 90 })
        assertEquals("AnalyzerConfig(maxFps=2.0, maxDimension=1280, jpegQuality=80)", config.toString())
    }

    @Test
    fun encodedFramesCompareByContent() {
        val frame = EncodedFrame(byteArrayOf(1, 2), 2, 1, 90, 7L)

        assertEquals(frame, EncodedFrame(byteArrayOf(1, 2), 2, 1, 90, 7L))
        assertEquals(frame.hashCode(), EncodedFrame(byteArrayOf(1, 2), 2, 1, 90, 7L).hashCode())
        assertNotEquals(frame, EncodedFrame(byteArrayOf(1, 3), 2, 1, 90, 7L))
        assertEquals(
            "EncodedFrame(bytes=2 bytes, width=2, height=1, rotationDegrees=90, timestampNanos=7)",
            frame.toString(),
            "toString names the JPEG's size, never its bytes",
        )
    }

    @Test
    fun theEncodedSizeKeepsTheAspectRatioAndNeverUpscales() {
        assertEquals(1280 to 720, encodedSize(2560, 1440, 1280))
        assertEquals(720 to 1280, encodedSize(1440, 2560, 1280))
        assertEquals(640 to 480, encodedSize(640, 480, 1280), "a smaller frame is not scaled up")
        assertEquals(1280 to 960, encodedSize(1280, 960, 1280), "a frame at the limit is kept")
        assertEquals(4000 to 3000, encodedSize(4000, 3000, null), "null keeps the full size")
        assertEquals(1280 to 1, encodedSize(5000, 2, 1280), "never below one pixel")
    }
}

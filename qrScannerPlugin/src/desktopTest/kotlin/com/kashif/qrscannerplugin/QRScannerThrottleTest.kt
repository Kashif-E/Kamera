package com.kashif.qrscannerplugin

import com.google.zxing.NotFoundException
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Desktop QR scanning gates decode attempts, not successes: with nothing in view (the normal case)
 * it decodes at most once per scan interval, and ZXing's slow TRY_HARDER pass runs only on a
 * periodic attempt, so an empty scene cannot pin a core.
 */
class QRScannerThrottleTest {
    private val frame = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)

    @Test
    fun anEmptyScene_isDecodedAtMostOncePerInterval() {
        var now = 10_000L
        val decoder = RecordingDecoder(text = null)
        val scanner = QRScanner({ now }, decoder)

        // One second of 30 fps frames.
        repeat(30) {
            scanner.scanImage(frame)
            now += 33
        }

        // Attempts at 0, 231, 462, 693 and 924 ms: the first frame at least 200 ms after the last attempt.
        assertEquals(5, decoder.attempts.size, "decode attempts in one second with nothing in view")
    }

    @Test
    fun aFoundCode_isThrottledTheSameWay() {
        var now = 10_000L
        val decoder = RecordingDecoder(text = "HELLO")
        val scanner = QRScanner({ now }, decoder)

        val results = List(30) {
            scanner.scanImage(frame).also { now += 33 }
        }

        assertEquals(5, decoder.attempts.size, "decode attempts in one second with a code in view")
        assertEquals(5, results.count { it == "HELLO" })
    }

    @Test
    fun tryHarder_runsOnlyOnAPeriodicSlowPass() {
        var now = 10_000L
        val decoder = RecordingDecoder(text = null)
        val scanner = QRScanner({ now }, decoder)

        repeat(10) {
            scanner.scanImage(frame)
            now += 1_000
        }

        val slow = decoder.attempts.count { it }
        assertEquals(10, decoder.attempts.size, "decode attempts")
        assertTrue(slow in 1..2, "TRY_HARDER ran on $slow of 10 empty-scene attempts")
    }

    /** Records each attempt's TRY_HARDER flag and finds [text], or nothing when it is null. */
    private class RecordingDecoder(private val text: String?) : QrFrameDecoder {
        val attempts = mutableListOf<Boolean>()

        override fun decode(image: BufferedImage, tryHarder: Boolean): String {
            attempts += tryHarder
            return text ?: throw NotFoundException.getNotFoundInstance()
        }
    }
}

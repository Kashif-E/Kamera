package com.kashif.qrscannerplugin

import com.google.zxing.ChecksumException
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.kashif.cameraK.utils.CameraKLogger
import java.awt.image.BufferedImage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Desktop QR scanning keeps quiet only about frames ZXing found no readable code in. Any other
 * decoder failure is logged with its throwable, at a rate limit so a failure on every frame does
 * not flood the log, instead of reading as "no code in view".
 */
class QRScannerFailureTest {
    private val frame = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
    private val errors = mutableListOf<Pair<String, Throwable?>>()

    @BeforeTest
    fun captureLogs() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, throwable ->
            if (level == CameraKLogger.Level.ERROR) errors += message to throwable
        }
    }

    @AfterTest
    fun restoreLogger() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun anUnexpectedDecoderFailure_isLoggedWithItsThrowable_atARateLimit() {
        var now = 10_000L
        val failure = IllegalStateException("decoder bug")
        val scanner = QRScanner({ now }, { _, _ -> throw failure })

        val results = List(10) {
            scanner.scanImage(frame).also { now += 1_000 }
        }

        assertTrue(results.all { it == null }, "a failed decode returned text: $results")
        val logged = errors.filter { (_, throwable) -> throwable === failure }
        assertEquals(1, logged.size, "logs of a decoder failure repeated on 10 attempts: $errors")
    }

    @Test
    fun framesWithNoReadableCode_areNotLogged() {
        var now = 10_000L
        val noCode = listOf(
            NotFoundException.getNotFoundInstance(),
            ChecksumException.getChecksumInstance(),
            FormatException.getFormatInstance(),
        )
        var next = 0
        val scanner = QRScanner({ now }, { _, _ -> throw noCode[next++ % noCode.size] })

        repeat(6) {
            assertNull(scanner.scanImage(frame))
            now += 1_000
        }

        assertTrue(errors.isEmpty(), "frames with no readable code were logged as errors: $errors")
    }
}

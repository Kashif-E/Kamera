package com.kashif.qrscannerplugin

import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.google.zxing.ChecksumException
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.kashif.cameraK.utils.CameraKLogger
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Android QR analyzer keeps quiet only about frames ZXing found no readable code in. A decoder
 * failure of any other kind is logged with its throwable, and an exception from the consumer's
 * callback is the consumer's, so it leaves the analyzer instead of being mistaken for an empty frame.
 */
@RunWith(RobolectricTestRunner::class)
class QRCodeAnalyzerFailureTest {
    private val errors = mutableListOf<Pair<String, Throwable?>>()

    @Before
    fun captureLogs() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, throwable ->
            if (level == CameraKLogger.Level.ERROR) errors += message to throwable
        }
    }

    @After
    fun restoreLogger() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun anUnexpectedDecoderFailure_isLoggedWithItsThrowable_atARateLimit() {
        val failure = IllegalStateException("decoder bug")
        val analyzer = QRCodeAnalyzer(onQrScanner = {}, decoder = { _, _, _ -> throw failure }, scanIntervalMs = 0)
        val frames = List(10) { FakeImageProxy() }

        frames.forEach(analyzer::analyze)

        val logged = errors.filter { (_, throwable) -> throwable === failure }
        assertEquals(1, logged.size, "logs of a decoder failure repeated on 10 frames: $errors")
        assertEquals(List(10) { 1 }, frames.map { it.closes }, "close count per frame")
    }

    @Test
    fun framesWithNoReadableCode_areNotLogged() {
        val noCode = listOf(
            NotFoundException.getNotFoundInstance(),
            ChecksumException.getChecksumInstance(),
            FormatException.getFormatInstance(),
        )
        var next = 0
        val analyzer =
            QRCodeAnalyzer(onQrScanner = {
            }, decoder = { _, _, _ -> throw noCode[next++ % noCode.size] }, scanIntervalMs = 0)

        repeat(6) { analyzer.analyze(FakeImageProxy()) }

        assertTrue(errors.isEmpty(), "frames with no readable code were logged as errors: $errors")
    }

    @Test
    fun anExceptionFromTheConsumersCallback_isNotSwallowed() {
        val consumerBug = IllegalArgumentException("consumer bug")
        val analyzer =
            QRCodeAnalyzer(onQrScanner = { throw consumerBug }, decoder = { _, _, _ -> "HELLO" }, scanIntervalMs = 0)
        val frame = FakeImageProxy()

        val thrown = assertFailsWith<IllegalArgumentException> { analyzer.analyze(frame) }

        assertSame(consumerBug, thrown)
        assertEquals(1, frame.closes, "the frame was not closed")
    }

    /** A YUV_420_888 frame with an empty Y plane. */
    private class FakeImageProxy : ImageProxy {
        var closes = 0

        override fun close() {
            closes++
        }

        override fun getCropRect(): Rect = Rect(0, 0, WIDTH, HEIGHT)

        override fun setCropRect(rect: Rect?) = Unit

        override fun getFormat(): Int = ImageFormat.YUV_420_888

        override fun getHeight(): Int = HEIGHT

        override fun getWidth(): Int = WIDTH

        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = arrayOf(
            plane(WIDTH * HEIGHT),
            plane(WIDTH * HEIGHT / 2),
            plane(
                WIDTH * HEIGHT / 2,
            ),
        )

        override fun getImageInfo(): ImageInfo = throw UnsupportedOperationException("not used by the analyzer")

        override fun getImage(): Image? = null

        private fun plane(size: Int) = object : ImageProxy.PlaneProxy {
            private val buffer = ByteBuffer.allocate(size)

            override fun getRowStride(): Int = WIDTH

            override fun getPixelStride(): Int = 1

            override fun getBuffer(): ByteBuffer = buffer
        }
    }

    private companion object {
        const val WIDTH = 16
        const val HEIGHT = 16
    }
}

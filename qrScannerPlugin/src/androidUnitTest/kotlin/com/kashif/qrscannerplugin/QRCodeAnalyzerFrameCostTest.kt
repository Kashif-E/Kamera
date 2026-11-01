package com.kashif.qrscannerplugin

import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Android QR analyzer runs on every analysis frame, so its steady state allocates nothing per
 * frame, decodes only inside the frame's crop rect, and keeps ZXing's slow TRY_HARDER pass to a
 * periodic attempt instead of every frame.
 */
@RunWith(RobolectricTestRunner::class)
class QRCodeAnalyzerFrameCostTest {
    private val decoder = RecordingDecoder()
    private val analyzer = QRCodeAnalyzer(onQrScanner = {}, decoder = decoder)

    @Test
    fun framesOfTheSameSize_reuseOneLumaBuffer() {
        repeat(3) { analyzer.analyze(FakeImageProxy()) }

        val buffers = decoder.attempts.filterNot { it.inverted }.map { it.data }
        assertEquals(3, buffers.size, "decode attempts")
        assertSame(buffers[0], buffers[1], "the second frame allocated a new luma buffer")
        assertSame(buffers[0], buffers[2], "the third frame allocated a new luma buffer")
    }

    @Test
    fun theLumaBufferHoldsTheFramesYPlane() {
        val proxy = FakeImageProxy()

        analyzer.analyze(proxy)

        val attempt = decoder.attempts.first()
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                assertEquals(
                    proxy.luma(x, y),
                    attempt.data[y * attempt.dataWidth + x],
                    "luma at ($x, $y)",
                )
            }
        }
    }

    @Test
    fun decoding_isConfinedToTheCropRect() {
        analyzer.analyze(FakeImageProxy(cropRect = Rect(8, 4, 40, 28)))

        val attempt = decoder.attempts.first()
        assertEquals(listOf(8, 4, 32, 24), listOf(attempt.left, attempt.top, attempt.width, attempt.height))
    }

    @Test
    fun tryHarder_runsOnlyOnAPeriodicSlowPass() {
        repeat(10) { analyzer.analyze(FakeImageProxy()) }

        val normal = decoder.attempts.filterNot { it.inverted }
        val slow = normal.count { it.tryHarder }
        assertEquals(10, normal.size, "decode attempts")
        assertTrue(slow in 1..2, "TRY_HARDER ran on $slow of 10 empty frames")
    }

    @Test
    fun everyFrame_isClosedOnce() {
        val proxies = List(4) { FakeImageProxy() }

        proxies.forEach(analyzer::analyze)

        assertEquals(List(4) { 1 }, proxies.map { it.closes })
    }

    private class Attempt(
        val data: ByteArray,
        val dataWidth: Int,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val tryHarder: Boolean,
        val inverted: Boolean,
    )

    private class RecordingDecoder : LumaDecoder {
        val attempts = mutableListOf<Attempt>()

        override fun decode(frame: LumaFrame, tryHarder: Boolean, inverted: Boolean): String? {
            attempts +=
                Attempt(
                    frame.data,
                    frame.dataWidth,
                    frame.left,
                    frame.top,
                    frame.width,
                    frame.height,
                    tryHarder,
                    inverted,
                )
            return null
        }
    }

    /** A YUV_420_888 frame whose Y plane has row padding, so a stride bug shows. */
    private class FakeImageProxy(private var cropRect: Rect = Rect(0, 0, WIDTH, HEIGHT)) : ImageProxy {
        var closes = 0

        private val yPlane =
            ByteBuffer.wrap(
                ByteArray(ROW_STRIDE * HEIGHT) { i ->
                    (i * 7 + 3).toByte()
                },
            ).also { it.position(ROW_STRIDE) }

        fun luma(x: Int, y: Int): Byte = yPlane.get(y * ROW_STRIDE + x)

        override fun close() {
            closes++
        }

        override fun getCropRect(): Rect = cropRect

        override fun setCropRect(rect: Rect?) {
            cropRect = rect ?: Rect(0, 0, WIDTH, HEIGHT)
        }

        override fun getFormat(): Int = ImageFormat.YUV_420_888

        override fun getHeight(): Int = HEIGHT

        override fun getWidth(): Int = WIDTH

        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = arrayOf(
            plane(yPlane, ROW_STRIDE),
            plane(ByteBuffer.allocate(ROW_STRIDE * HEIGHT / 2), ROW_STRIDE),
            plane(ByteBuffer.allocate(ROW_STRIDE * HEIGHT / 2), ROW_STRIDE),
        )

        override fun getImageInfo(): ImageInfo = throw UnsupportedOperationException("not used by the analyzer")

        override fun getImage(): Image? = null

        private fun plane(buffer: ByteBuffer, rowStride: Int) = object : ImageProxy.PlaneProxy {
            override fun getRowStride(): Int = rowStride

            override fun getPixelStride(): Int = 1

            override fun getBuffer(): ByteBuffer = buffer
        }
    }

    private companion object {
        const val WIDTH = 48
        const val HEIGHT = 32
        const val ROW_STRIDE = 64
    }
}

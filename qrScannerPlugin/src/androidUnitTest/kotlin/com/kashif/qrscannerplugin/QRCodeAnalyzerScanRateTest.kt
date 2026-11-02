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

/**
 * The Android QR analyzer gates decode attempts at a configurable rate, so a 30 fps stream with
 * nothing in view costs one decode per interval, and a frame inside the interval is closed without
 * being read.
 */
@RunWith(RobolectricTestRunner::class)
class QRCodeAnalyzerScanRateTest {
    private var now = 10_000L
    private val attempts = mutableListOf<Long>()
    private val decoder = LumaDecoder { _, _, inverted ->
        if (!inverted) attempts += now
        null
    }

    @Test
    fun anEmptyScene_isDecodedAtMostOncePerInterval() {
        val analyzer = QRCodeAnalyzer(onQrScanner = {}, decoder = decoder, clock = { now }, scanIntervalMs = 200)
        val frames = List(30) { FakeImageProxy() }

        frames.forEach {
            analyzer.analyze(it)
            now += 33
        }

        // Attempts at 0, 231, 462, 693 and 924 ms: the first frame at least 200 ms after the last attempt.
        assertEquals(5, attempts.size, "decode attempts in one second of 30 fps frames")
        assertEquals(List(30) { 1 }, frames.map { it.closes }, "close count per frame")
        assertEquals(5, frames.count { it.planesRead > 0 }, "frames whose pixels were read")
    }

    @Test
    fun byDefault_attemptsAreGated() {
        val analyzer = QRCodeAnalyzer(onQrScanner = {}, decoder = decoder, clock = { now })

        repeat(30) {
            analyzer.analyze(FakeImageProxy())
            now += 33
        }

        assertEquals(5, attempts.size, "decode attempts in one second of 30 fps frames at the default interval")
    }

    /** A YUV_420_888 frame with an empty Y plane that counts reads of its planes. */
    private class FakeImageProxy : ImageProxy {
        var closes = 0
        var planesRead = 0

        override fun close() {
            closes++
        }

        override fun getCropRect(): Rect = Rect(0, 0, SIDE, SIDE)

        override fun setCropRect(rect: Rect?) = Unit

        override fun getFormat(): Int = ImageFormat.YUV_420_888

        override fun getHeight(): Int = SIDE

        override fun getWidth(): Int = SIDE

        override fun getPlanes(): Array<ImageProxy.PlaneProxy> {
            planesRead++
            return arrayOf(plane(SIDE * SIDE), plane(SIDE * SIDE / 2), plane(SIDE * SIDE / 2))
        }

        override fun getImageInfo(): ImageInfo = throw UnsupportedOperationException("not used by the analyzer")

        override fun getImage(): Image? = null

        private fun plane(size: Int) = object : ImageProxy.PlaneProxy {
            private val buffer = ByteBuffer.allocate(size)

            override fun getRowStride(): Int = SIDE

            override fun getPixelStride(): Int = 1

            override fun getBuffer(): ByteBuffer = buffer
        }
    }

    private companion object {
        const val SIDE = 16
    }
}

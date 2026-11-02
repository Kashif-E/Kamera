package com.kashif.ocrPlugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live OCR on desktop does not run Tesseract on every camera frame: by default it scans at most two
 * frames a second, however fast frames arrive.
 */
class DesktopLiveOcrRateTest {
    @Test
    fun byDefault_framesAreScannedAtMostTwiceASecond() = runBlocking {
        val scans = AtomicInteger()
        val allDelivered = CompletableDeferred<Unit>()
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        val frames =
            flow {
                repeat(FRAMES) { emit(image) }
                allDelivered.complete(Unit)
            }
        val scan: (BufferedImage) -> String? = {
            scans.incrementAndGet()
            null
        }
        val start = System.nanoTime()

        val handle = startRecognition(frames, scan, {}, {})
        withTimeout(5_000) { allDelivered.await() }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        handle.stop()

        val allowed = 1 + elapsedMs / 500
        assertTrue(
            scans.get() <= allowed,
            "${scans.get()} of $FRAMES frames scanned in $elapsedMs ms; at most $allowed allowed",
        )
    }

    private companion object {
        const val FRAMES = 30
    }
}

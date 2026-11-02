package com.kashif.ocrPlugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Live OCR on desktop does not run Tesseract on every camera frame: by default it scans at most two
 * frames a second, however fast frames arrive, and OcrConfig.maxFps sets that rate.
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

    @Test
    fun byDefault_twoFramesOfASecondAreScanned() = runBlocking {
        val scans = AtomicInteger()

        runOneSecondOfFrames(DEFAULT_LIVE_OCR_MAX_FPS, scan = { scans.incrementAndGet() })

        assertEquals(2, scans.get(), "frames scanned in one second of $FRAMES_PER_SECOND fps frames")
    }

    @Test
    fun atMaxFps10_tesseractReadsTenFramesOfASecond() = runBlocking {
        val tesseract = FakeTesseract()
        val processor = OCRProcessor(tesseract, "tessdata")
        val texts = AtomicInteger()

        runOneSecondOfFrames(
            maxFps = 10,
            scan = { image -> if (processor.scanImage(image) != null) texts.incrementAndGet() },
            close = processor::close,
        )

        assertEquals(
            10,
            texts.get(),
            "frames Tesseract read in one second of $FRAMES_PER_SECOND fps frames at maxFps 10",
        )
    }

    /**
     * Feeds one second of [FRAMES_PER_SECOND] fps frames, stamped on a clock of the test's own, through
     * live recognition at [maxFps], and returns once every frame has been handled.
     */
    private suspend fun runOneSecondOfFrames(maxFps: Int, scan: (BufferedImage) -> Unit, close: () -> Unit = {}) {
        val now = AtomicLong()
        val allDelivered = CompletableDeferred<Unit>()
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        val frames =
            flow {
                repeat(FRAMES_PER_SECOND) { i ->
                    now.set(i * FRAME_INTERVAL_NANOS)
                    emit(image)
                }
                allDelivered.complete(Unit)
            }

        val handle =
            startRecognition(
                frames,
                { frame ->
                    scan(frame)
                    // No text reaches onText, so the test needs no Main dispatcher.
                    null
                },
                close,
                maxFps,
                now::get,
                {},
            )
        try {
            withTimeout(5_000) { allDelivered.await() }
        } finally {
            handle.stop()
        }
    }

    private companion object {
        const val FRAMES = 30
        const val FRAMES_PER_SECOND = 50
        const val FRAME_INTERVAL_NANOS = 1_000_000_000L / FRAMES_PER_SECOND
    }
}

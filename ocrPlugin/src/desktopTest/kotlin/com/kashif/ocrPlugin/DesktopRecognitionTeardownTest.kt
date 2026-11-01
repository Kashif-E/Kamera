package com.kashif.ocrPlugin

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.image.BufferedImage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stopping recognition frees the Tesseract handle only after the scan that is using it returns,
 * exactly once, and without making the caller wait for that scan.
 */
class DesktopRecognitionTeardownTest {

    @Test
    fun stop_whileAScanIsInFlight_returnsAtOnceAndClosesTheScannerOnlyAfterTheScanReturns() {
        val frames = MutableSharedFlow<BufferedImage>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val scanner = BlockingScanner()
        val handle = startRecognition(frames, scanner::scan, scanner::close) {}
        try {
            runBlocking { withTimeout(5_000) { frames.subscriptionCount.first { it > 0 } } }
            frames.tryEmit(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB))
            assertTrue(scanner.scanEntered.await(5, TimeUnit.SECONDS), "the collector never scanned a frame")

            // The scan is inside SetImage/GetUTF8Text. RecognitionHandle.stop() is called from
            // onDetach on the caller's thread, so it must not wait for Tesseract either.
            handle.stop()
            handle.stop()
            assertTrue(scanner.isScanInFlight, "stop() blocked until the in-flight scan returned")
            scanner.finishScan()

            assertTrue(scanner.closed.await(5, TimeUnit.SECONDS), "the scanner was never closed")
            assertEquals(emptyList(), scanner.violations.toList())
            assertEquals(1, scanner.closeCount.get(), "close() count")
        } finally {
            scanner.finishScan()
            handle.stop()
        }
    }

    /** A scanner with no locking, like TessBaseAPI: it records End() while a scan is still inside it. */
    private class BlockingScanner {
        private val gate = CountDownLatch(1)
        private val inFlight = AtomicInteger(0)

        @Volatile private var ended = false

        val violations: MutableList<String> = CopyOnWriteArrayList()
        val closeCount = AtomicInteger(0)
        val scanEntered = CountDownLatch(1)
        val closed = CountDownLatch(1)

        val isScanInFlight: Boolean get() = inFlight.get() > 0

        fun finishScan() = gate.countDown()

        fun scan(image: BufferedImage): String? {
            if (ended) violations += "scan after close()"
            inFlight.incrementAndGet()
            try {
                scanEntered.countDown()
                gate.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                return null
            } finally {
                inFlight.decrementAndGet()
            }
        }

        fun close() {
            closeCount.incrementAndGet()
            if (inFlight.get() > 0) violations += "close() while a scan was in flight"
            ended = true
            closed.countDown()
        }
    }

    private companion object {
        // Upper bound so a broken test cannot hang the suite; the test opens the gate long before this.
        const val GATE_TIMEOUT_SECONDS = 10L
    }
}

package com.kashif.ocrPlugin

import java.awt.image.BufferedImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OCRProcessor is public, so close() can come from any thread. It must not free Tesseract while a
 * scan is inside it, and a closed processor must not touch Tesseract again.
 */
class OcrProcessorTeardownTest {

    @Test
    fun close_whileAScanIsInFlight_waitsForTheScanBeforeEndingTesseract() {
        val tesseract = FakeTesseract().apply { recognitionGate = CountDownLatch(1) }
        val processor = OCRProcessor(tesseract, TESSDATA)
        val scanResult = AtomicReference<String?>()
        val scanner = thread(name = "ocr-scan") { scanResult.set(processor.scanImage(frame())) }
        try {
            assertTrue(tesseract.recognitionEntered.await(5, TimeUnit.SECONDS), "the scan never reached Tesseract")

            val closer = thread(name = "ocr-close") { processor.close() }
            // Either close() has returned, or it is parked waiting for the scan.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (closer.isAlive && closer.state == Thread.State.RUNNABLE && System.nanoTime() < deadline) {
                Thread.onSpinWait()
            }
            assertEquals(emptyList(), tesseract.violations.toList())
            assertTrue(closer.isAlive, "close() returned while the scan was still inside Tesseract")

            tesseract.recognitionGate?.countDown()
            scanner.join(5_000)
            closer.join(5_000)
            assertEquals("HELLO", scanResult.get())
            assertEquals(1, tesseract.engines.single().ends.get(), "End() count")
            assertEquals(emptyList(), tesseract.violations.toList())
        } finally {
            tesseract.recognitionGate?.countDown()
        }
    }

    @Test
    fun scanImage_afterClose_returnsNullWithoutTouchingTesseract() {
        val tesseract = FakeTesseract()
        val processor = OCRProcessor(tesseract, TESSDATA)
        processor.close()
        val crossingsAfterClose = tesseract.crossings.get()

        assertNull(processor.scanImage(frame()))

        assertEquals(emptyList(), tesseract.violations.toList())
        assertEquals(crossingsAfterClose, tesseract.crossings.get(), "native calls after close()")
    }

    @Test
    fun close_twice_endsTesseractOnce() {
        val tesseract = FakeTesseract()
        val processor = OCRProcessor(tesseract, TESSDATA)

        processor.close()
        processor.close()

        assertEquals(1, tesseract.engines.single().ends.get(), "End() count")
        assertEquals(emptyList(), tesseract.violations.toList())
    }

    private fun frame() = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)

    private companion object {
        const val TESSDATA = "tessdata"
    }
}

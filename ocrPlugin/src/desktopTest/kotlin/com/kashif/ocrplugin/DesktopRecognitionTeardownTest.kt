package com.kashif.ocrPlugin

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ending live recognition frees the Tesseract handle only after the scan that is using it returns,
 * exactly once, and without making the caller wait for that scan.
 */
class DesktopRecognitionTeardownTest {

    @Test
    fun stop_whileAScanIsInFlight_returnsAtOnceAndClosesTheScannerOnlyAfterTheScanReturns() = runTest {
        val tesseract = FakeTesseract().apply { recognitionGate = CountDownLatch(1) }
        val plugin = OcrPlugin(OcrConfig(), DesktopTextRecognizers(tesseract) { "tessdata" })
        val session = ocrSession(plugin)
        startReady(session)
        // The frame source delivers on the emitting thread, as a platform source's worker would.
        val worker = thread(name = "ocr-frames") { session.engine.fakeFrames.emit(countingFrame(8, 8)) }
        try {
            assertTrue(tesseract.recognitionEntered.await(5, TimeUnit.SECONDS), "the frame never reached Tesseract")

            // The scan is inside GetUTF8Text. pause() comes from the app's thread, so it must not
            // wait for Tesseract, and neither must the session's teardown.
            plugin.pause()
            plugin.pause()
            runCurrent()
            val engine = tesseract.engines.single()
            assertEquals(0, engine.ends.get(), "End() while the scan was still in flight")
            assertEquals(0, session.frames.open, "registrations after pause")
            tesseract.recognitionGate?.countDown()
            worker.join(5_000)

            assertEquals(emptyList(), tesseract.violations.toList())
            assertEquals(1, engine.ends.get(), "End() count")
            assertEquals(1, engine.deletes.get(), "TessBaseAPI free count")
        } finally {
            tesseract.recognitionGate?.countDown()
            worker.join(5_000)
        }
    }
}

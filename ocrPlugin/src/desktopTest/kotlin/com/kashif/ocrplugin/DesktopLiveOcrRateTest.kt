package com.kashif.ocrPlugin

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Live OCR on desktop does not run Tesseract on every camera frame: by default it scans at most two
 * frames a second, however fast frames arrive, and OcrConfig.maxFps sets that rate. The rate is the
 * shared frame gate's, clocked by the frames' timestamps.
 */
class DesktopLiveOcrRateTest {
    @Test
    fun byDefault_framesAreScannedAtMostTwiceASecond() = runTest {
        val tesseract = FakeTesseract()
        val session = ocrSession(OcrPlugin(OcrConfig(), DesktopTextRecognizers(tesseract) { "tessdata" }))
        startReady(session)
        val start = System.nanoTime()

        repeat(FRAMES) { session.engine.fakeFrames.emit(countingFrame(8, 8, timestampNanos = System.nanoTime())) }

        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        val allowed = 1 + elapsedMs / 500
        assertTrue(
            tesseract.texts.size <= allowed,
            "${tesseract.texts.size} of $FRAMES frames scanned in $elapsedMs ms; at most $allowed allowed",
        )
    }

    @Test
    fun byDefault_twoFramesOfASecondAreScanned() = runTest {
        val tesseract = FakeTesseract()

        runOneSecondOfFrames(OcrConfig(), tesseract)

        assertEquals(2, tesseract.texts.size, "frames scanned in one second of $FRAMES_PER_SECOND fps frames")
    }

    @Test
    fun atMaxFps10_tesseractReadsTenFramesOfASecond() = runTest {
        val tesseract = FakeTesseract()

        val texts = runOneSecondOfFrames(OcrConfig { maxFps = 10f }, tesseract)

        assertEquals(
            10,
            texts.size,
            "frames Tesseract read in one second of $FRAMES_PER_SECOND fps frames at maxFps 10",
        )
    }

    /** Feeds one second of [FRAMES_PER_SECOND] fps frames through live recognition; returns the texts emitted. */
    private fun TestScope.runOneSecondOfFrames(config: OcrConfig, tesseract: FakeTesseract): List<RecognizedText> {
        val plugin = OcrPlugin(config, DesktopTextRecognizers(tesseract) { "tessdata" })
        val session = ocrSession(plugin)
        val texts = mutableListOf<RecognizedText>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) {
            plugin.results.collect { texts += it }
        }
        startReady(session)
        repeat(FRAMES_PER_SECOND) { i ->
            session.engine.fakeFrames.emit(countingFrame(8, 8, timestampNanos = i * FRAME_INTERVAL_NANOS))
        }
        return texts
    }

    private companion object {
        const val FRAMES = 30
        const val FRAMES_PER_SECOND = 50
        const val FRAME_INTERVAL_NANOS = 1_000_000_000L / FRAMES_PER_SECOND
    }
}

package com.kashif.ocrPlugin

import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.test.runTest
import org.bytedeco.tesseract.global.tesseract.OEM_DEFAULT
import org.bytedeco.tesseract.global.tesseract.OEM_LSTM_ONLY
import org.bytedeco.tesseract.global.tesseract.OEM_TESSERACT_ONLY
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Desktop live OCR hands Tesseract the frame's luma turned upright, and the level picks Tesseract's
 * engine: legacy at FAST, falling back to the default engine with one log line when the language
 * data has none, and LSTM at ACCURATE.
 */
class DesktopLiveOcrInputTest {
    @AfterTest
    fun restoreLogger() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun aRotatedFrameReachesTesseractUpright() = runTest {
        val tesseract = FakeTesseract()
        val session = ocrSession(OcrPlugin(OcrConfig(), DesktopTextRecognizers(tesseract) { "tessdata" }))
        startReady(session)

        // Buffer rows [0 1 2] [3 4 5], turned 90 degrees clockwise: rows [3 0] [4 1] [5 2].
        session.engine.fakeFrames.emit(countingFrame(width = 3, height = 2, rotationDegrees = 90))

        assertEquals(FakeTesseract.GrayImage(2, 3, listOf(3, 0, 4, 1, 5, 2)), tesseract.lastImage)
    }

    @Test
    fun levelReachesTheRecognizer() = runTest {
        val tesseract = FakeTesseract()
        val recognizers = DesktopTextRecognizers(tesseract) { "tessdata" }

        recognizers.live(OcrLevel.FAST).close()
        recognizers.live(OcrLevel.ACCURATE).close()

        assertEquals(listOf(OEM_TESSERACT_ONLY, OEM_LSTM_ONLY), tesseract.initModes.toList(), "engine modes")
    }

    @Test
    fun withoutALegacyEngineFastRunsTheDefaultEngineAndLogsOnce() {
        val warnings = mutableListOf<String>()
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, _ -> if (level == CameraKLogger.Level.WARN) warnings += message }
        val tesseract = FakeTesseract(legacyInitResult = -1)
        val logged = AtomicBoolean(false)

        repeat(2) { OCRProcessor(tesseract, "tessdata", OcrLevel.FAST, logged).close() }

        assertEquals(
            listOf(OEM_TESSERACT_ONLY, OEM_DEFAULT, OEM_TESSERACT_ONLY, OEM_DEFAULT),
            tesseract.initModes.toList(),
            "engine modes",
        )
        assertEquals(1, warnings.size, "warnings for two processors: $warnings")
        assertEquals(emptyList(), tesseract.violations.toList())
    }
}

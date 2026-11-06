package com.kashif.ocrPlugin

import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSError
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * When Vision fails on a live frame, the failure is logged once with its cause, and live OCR reads
 * the next frame.
 */
@OptIn(ExperimentalForeignApi::class)
class LiveOcrFailureTest {
    private val errors = mutableListOf<Pair<String, Throwable?>>()

    @BeforeTest
    fun captureLogs() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, throwable ->
            if (level == CameraKLogger.Level.ERROR) errors += message to throwable
        }
    }

    @AfterTest
    fun restoreLogger() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun aVisionErrorReportedToTheRequest_isLoggedOnceWithItsCause() = runTest {
        val session = ocrSession(
            plugin { _, _, request ->
                request.completionHandler?.invoke(request, visionError())
                null
            },
        )
        startReady(session)

        session.engine.fakeFrames.emit(countingFrame(16, 16))

        assertEquals(1, errors.size, "error lines for one failed frame: $errors")
        assertNotNull(errors.single().second, "the failure was logged without its cause")
    }

    @Test
    fun visionFailingWithoutCallingBack_isLoggedAndTheNextFrameIsRead() = runTest {
        var framesHandedToVision = 0
        val session = ocrSession(
            plugin { _, _, _ ->
                framesHandedToVision++
                visionError()
            },
        )
        startReady(session)

        session.engine.fakeFrames.emit(countingFrame(16, 16, timestampNanos = 0))
        session.engine.fakeFrames.emit(countingFrame(16, 16, timestampNanos = SECOND_NANOS))

        assertEquals(2, framesHandedToVision, "frames handed to Vision after the first failed without calling back")
        assertEquals(2, errors.size, "error lines for two failed frames: $errors")
        assertTrue(errors.all { it.second != null }, "a failure was logged without its cause: $errors")
    }

    @Test
    fun anExceptionWhileReadingAFrame_isLoggedWithItsCause() = runTest {
        val failure = IllegalStateException("Vision could not run")
        val session = ocrSession(plugin { _, _, _ -> throw failure })
        startReady(session)

        session.engine.fakeFrames.emit(countingFrame(16, 16))

        assertEquals(1, errors.size, "error lines for one failed frame: $errors")
        assertSame(failure, errors.single().second)
    }

    private fun plugin(performer: LiveVisionPerformer) =
        OcrPlugin(OcrConfig(), AppleTextRecognizers(livePerformer = performer))

    private fun visionError() = NSError.errorWithDomain("com.apple.Vision", 9, null)
}

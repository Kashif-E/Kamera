package com.kashif.ocrplugin

import com.google.android.gms.tasks.Tasks
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Live OCR logs what it did and how much it read, never the text: the log sink may be forwarded to
 * a crash reporter. A failure is logged once, with its throwable.
 */
@RunWith(RobolectricTestRunner::class)
class LiveOcrLogTest {
    private class Line(val level: CameraKLogger.Level, val message: String, val throwable: Throwable?)

    private val lines = mutableListOf<Line>()

    @Before
    fun captureLogs() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, throwable ->
            synchronized(lines) { lines += Line(level, message, throwable) }
        }
    }

    @After
    fun restoreLogger() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun recognisedText_isNotLogged() = runTest {
        val plugin =
            OcrPlugin(OcrConfig(), AndroidTextRecognizers(newClient = { FakeMlKitClient { textTask(SECRET) } }))
        val session = ocrSession(plugin)
        val received = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) {
            plugin.results.collect { received += it.text }
        }
        startReady(session)

        session.engine.fakeFrames.emit(countingFrame())

        assertEquals(listOf(SECRET), received, "the text still reaches the plugin's results")
        val messages = synchronized(lines) { lines.map { it.message } }
        assertTrue(messages.isNotEmpty(), "live OCR logged nothing, so the check below proves nothing")
        assertTrue(messages.none { SECRET_PART in it }, "log lines carrying the text: $messages")
    }

    @Test
    fun aRecognitionFailure_isLoggedOnceWithItsThrowable() = runTest {
        val failure = IllegalStateException("model not downloaded")
        val plugin =
            OcrPlugin(
                OcrConfig(),
                AndroidTextRecognizers(newClient = {
                    FakeMlKitClient { Tasks.forException(failure) }
                }),
            )
        val session = ocrSession(plugin)
        startReady(session)

        session.engine.fakeFrames.emit(countingFrame())

        val errors = synchronized(lines) { lines.filter { it.level == CameraKLogger.Level.ERROR } }
        assertEquals(1, errors.size, "error lines for one failure: ${errors.map { it.message }}")
        assertSame(failure, errors.single().throwable)
    }

    private companion object {
        const val SECRET = "IBAN DE89 3704 0044 0532 0130 00"
        const val SECRET_PART = "0532 0130"
    }
}

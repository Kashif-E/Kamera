package com.kashif.ocrPlugin

import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** One-shot OCR through the plugin never writes the text it read to the log: the sink may go to a crash reporter. */
@RunWith(RobolectricTestRunner::class)
class OcrExtractTextTest {
    private val messages = CopyOnWriteArrayList<String>()

    @Before
    fun captureLogs() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { _, _, message, _ -> messages += message }
    }

    @After
    fun restoreLogger() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun recognize_returnsTheRecognisedTextToTheCaller_withoutLoggingIt() = runBlocking {
        val plugin = OcrPlugin(
            OcrConfig(),
            AndroidTextRecognizers(newClient = { FakeMlKitClient { textTask(SECRET) } }, decode = { stillBitmap() }),
        )

        val result = plugin.recognize(capture())

        assertEquals(SECRET, assertIs<CameraKResult.Success<RecognizedText>>(result).value.text)
        assertTrue(messages.none { SECRET_PART in it }, "log lines carrying the recognised text: $messages")
    }

    private companion object {
        const val SECRET = "OTP 493017 for card 4111"
        const val SECRET_PART = "493017"
    }
}

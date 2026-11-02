package com.kashif.ocrPlugin

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
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

    @Suppress("DEPRECATION")
    @Test
    fun extractTextFromBitmap_doesNotLogTheRecognisedText() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val plugin =
                OcrPlugin(
                    coroutineScope = scope,
                    config = OcrConfig(),
                    recognizeBitmap = { _, _ -> SECRET },
                    startRecognizer = { _, _, _ -> RecognitionHandle {} },
                )

            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap()
            withTimeout(5_000) { plugin.extractTextFromBitmap(bitmap).join() }

            assertTrue(messages.isNotEmpty(), "one-shot OCR logged nothing, so the check below proves nothing")
            assertTrue(messages.none { SECRET_PART in it }, "log lines carrying the recognised text: $messages")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun recognize_returnsTheRecognisedTextToTheCaller_withoutLoggingIt() = runBlocking {
        val plugin =
            OcrPlugin(
                coroutineScope = this,
                config = OcrConfig(),
                recognizeBitmap = { _, _ -> SECRET },
                startRecognizer = { _, _, _ -> RecognitionHandle {} },
            )

        val text = plugin.recognize(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap())

        assertEquals(SECRET, text)
        assertTrue(messages.none { SECRET_PART in it }, "log lines carrying the recognised text: $messages")
    }

    private companion object {
        const val SECRET = "OTP 493017 for card 4111"
        const val SECRET_PART = "493017"
    }
}

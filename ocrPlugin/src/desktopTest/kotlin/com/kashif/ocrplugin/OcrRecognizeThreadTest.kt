package com.kashif.ocrPlugin

import com.kashif.cameraK.result.CameraKResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * One-shot OCR does not run the recognizer on the thread that calls recognize(), which is Main for
 * a Compose caller: on desktop the recognizer is Tesseract, which blocks for as long as it reads.
 */
class OcrRecognizeThreadTest {
    @Test
    fun recognize_runsTheRecognizerOffTheCallersThread() = runBlocking {
        val callerExecutor = Executors.newSingleThreadExecutor { Thread(it, "recognize-caller") }
        try {
            val recognizerThread = CompletableDeferred<Thread>()
            val recognizers = FakeRecognizers().apply {
                stillAnswer = {
                    recognizerThread.complete(Thread.currentThread())
                    listOf(FoundBlock("TEXT", null, null))
                }
            }
            val plugin = OcrPlugin(OcrConfig(), recognizers)

            val (callerThread, result) =
                withTimeout(5_000) {
                    withContext(callerExecutor.asCoroutineDispatcher()) {
                        Thread.currentThread() to plugin.recognize(capture())
                    }
                }

            assertEquals("TEXT", assertIs<CameraKResult.Success<RecognizedText>>(result).value.text)
            assertNotEquals(
                callerThread,
                recognizerThread.await(),
                "the recognizer ran on the thread that called recognize",
            )
        } finally {
            callerExecutor.shutdown()
        }
    }
}

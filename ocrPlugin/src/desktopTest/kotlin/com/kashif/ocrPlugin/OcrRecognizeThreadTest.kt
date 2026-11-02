package com.kashif.ocrPlugin

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * One-shot OCR does not run the recognizer on the thread that calls recognize(), which is Main for
 * a Compose caller: on desktop the recognizer is Tesseract, which blocks for as long as it reads.
 */
class OcrRecognizeThreadTest {
    @Test
    fun recognize_runsTheRecognizerOffTheCallersThread() = runBlocking {
        val callerExecutor = Executors.newSingleThreadExecutor { Thread(it, "recognize-caller") }
        val scope = CoroutineScope(Job())
        try {
            val recognizerThread = CompletableDeferred<Thread>()
            val plugin =
                OcrPlugin(
                    coroutineScope = scope,
                    config = OcrConfig(),
                    recognizeBitmap = { _, _ ->
                        recognizerThread.complete(Thread.currentThread())
                        "TEXT"
                    },
                    startRecognizer = { _, _, _ -> RecognitionHandle {} },
                )

            val (callerThread, text) =
                withTimeout(5_000) {
                    withContext(callerExecutor.asCoroutineDispatcher()) {
                        Thread.currentThread() to plugin.recognize(BlankBitmap)
                    }
                }

            assertEquals("TEXT", text)
            assertNotEquals(
                callerThread,
                recognizerThread.await(),
                "the recognizer ran on the thread that called recognize",
            )
        } finally {
            scope.cancel()
            callerExecutor.shutdown()
        }
    }

    /** An ImageBitmap the recognizer never reads; a real one needs Skia's native library. */
    private object BlankBitmap : ImageBitmap {
        override val width = 1
        override val height = 1
        override val colorSpace: ColorSpace = ColorSpaces.Srgb
        override val hasAlpha = false
        override val config = ImageBitmapConfig.Argb8888

        override fun readPixels(
            buffer: IntArray,
            startX: Int,
            startY: Int,
            width: Int,
            height: Int,
            bufferOffset: Int,
            stride: Int,
        ) = Unit

        override fun prepareToDraw() = Unit
    }
}

package com.kashif.ocrPlugin

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * One-shot OCR closes the ML Kit recognizer it creates exactly once, whether recognition succeeds,
 * fails, is cancelled or never starts, so no native model handle outlives the call.
 */
@RunWith(RobolectricTestRunner::class)
class OneShotRecognizerLeakTest {
    private val created = CopyOnWriteArrayList<FakeRecognizer>()

    @Test
    fun success_closesTheRecognizerOnce() = runBlocking {
        val text = extractText(bitmap(), recognizers { Tasks.forResult("HELLO") })

        assertEquals("HELLO", text)
        assertEquals(listOf(1), closes())
    }

    @Test
    fun failure_closesTheRecognizerOnce() = runBlocking {
        assertFailsWith<RecognitionFailure> {
            extractText(bitmap(), recognizers { Tasks.forException(RecognitionFailure()) })
        }

        assertEquals(listOf(1), closes())
    }

    @Test
    fun invalidDimensions_leaveNoOpenRecognizer() = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            extractText(EmptyImageBitmap, recognizers { Tasks.forResult("unused") })
        }

        assertEquals(List(created.size) { 1 }, closes(), "close count per recognizer created")
    }

    @Test
    fun cancellation_closesTheRecognizerOnce() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val extraction =
            async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                extractText(
                    bitmap(),
                    recognizers {
                        started.complete(Unit)
                        TaskCompletionSource<String>().task
                    },
                )
            }
        withTimeout(5_000) { started.await() }

        extraction.cancel()
        extraction.join()

        assertEquals(listOf(1), closes())
    }

    private fun closes() = created.map { it.closes.get() }

    private fun recognizers(outcome: () -> Task<String>): () -> BitmapTextRecognizer =
        { FakeRecognizer(outcome).also(created::add) }

    private fun bitmap() = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap()

    // Coroutines may rethrow a copy of the exception for its stack trace, so the test checks the type.
    class RecognitionFailure : Exception("recognition failed")

    class FakeRecognizer(private val outcome: () -> Task<String>) : BitmapTextRecognizer {
        val closes = AtomicInteger()

        override fun recognize(bitmap: Bitmap): Task<String> = outcome()

        override fun close() {
            closes.incrementAndGet()
        }
    }

    private object EmptyImageBitmap : ImageBitmap {
        override val width = 0
        override val height = 0
        override val config = ImageBitmapConfig.Argb8888
        override val colorSpace: ColorSpace = ColorSpaces.Srgb
        override val hasAlpha = true

        override fun prepareToDraw() = Unit

        override fun readPixels(
            buffer: IntArray,
            startX: Int,
            startY: Int,
            width: Int,
            height: Int,
            bufferOffset: Int,
            stride: Int,
        ) = Unit
    }
}

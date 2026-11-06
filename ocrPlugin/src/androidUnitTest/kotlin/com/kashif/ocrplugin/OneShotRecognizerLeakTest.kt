package com.kashif.ocrplugin

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
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
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * One-shot OCR closes the ML Kit client it creates exactly once, whether recognition succeeds,
 * fails, is cancelled or never starts, so no native model handle outlives the call.
 */
@RunWith(RobolectricTestRunner::class)
class OneShotRecognizerLeakTest {
    private val created = CopyOnWriteArrayList<FakeMlKitClient>()

    @Test
    fun success_closesTheRecognizerOnce() = runBlocking {
        val result = plugin { textTask("HELLO") }.recognize(capture())

        assertEquals("HELLO", assertIs<CameraKResult.Success<RecognizedText>>(result).value.text)
        assertEquals(listOf(1), closes())
    }

    @Test
    fun failure_closesTheRecognizerOnce() = runBlocking {
        val result = plugin { Tasks.forException(RecognitionFailure()) }.recognize(capture())

        val error = assertIs<CameraKError.Internal>(assertIs<CameraKResult.Failure>(result).error)
        assertIs<RecognitionFailure>(error.cause)
        assertEquals(listOf(1), closes())
    }

    @Test
    fun invalidDimensions_leaveNoOpenRecognizer() = runBlocking {
        // A file that does not decode: the recognizer is never needed.
        val result = plugin(decode = { null }) { Tasks.forResult(emptyList()) }.recognize(capture())

        assertIs<CameraKResult.Failure>(result)
        assertEquals(List(created.size) { 1 }, closes(), "close count per recognizer created")
    }

    @Test
    fun cancellation_closesTheRecognizerOnce() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val plugin = plugin {
            started.complete(Unit)
            TaskCompletionSource<List<FoundBlock>>().task
        }
        val extraction = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { plugin.recognize(capture()) }
        withTimeout(5_000) { started.await() }

        extraction.cancel()
        extraction.join()

        assertEquals(listOf(1), closes())
    }

    private fun closes() = created.map { it.closes.get() }

    private fun plugin(
        decode: (String) -> DecodedStill? = { stillBitmap() },
        outcome: () -> Task<List<FoundBlock>>,
    ): OcrPlugin = OcrPlugin(
        OcrConfig(),
        AndroidTextRecognizers(newClient = {
            FakeMlKitClient(outcome).also(created::add)
        }, decode = decode),
    )

    // Coroutines may rethrow a copy of the exception for its stack trace, so the test checks the type.
    class RecognitionFailure : Exception("recognition failed")
}

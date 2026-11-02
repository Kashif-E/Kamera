package com.kashif.ocrPlugin

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSError
import platform.Foundation.NSThread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * One-shot OCR never runs Vision on the caller's thread, which is Main for a Compose caller, and it
 * always completes, even when Vision fails without calling its completion handler.
 */
class OneShotOcrThreadingTest {

    @Test
    fun vision_runsOffTheCallersThread() = runBlocking<Unit> {
        val caller = NSThread.currentThread
        val visionThread = CompletableDeferred<NSThread>()

        val text = withTimeout(TIMEOUT_MS) {
            recognizeText(ImageBitmap(8, 8)) { _, request ->
                visionThread.complete(NSThread.currentThread)
                request.completionHandler?.invoke(request, null)
                null
            }
        }

        assertEquals("", text)
        // == is isEqual, which compares the NSThreads themselves; two Kotlin references to one
        // NSThread need not be the same object.
        assertNotEquals(caller, visionThread.await(), "Vision ran on the thread that called extractText")
    }

    @Test
    fun visionFailingWithoutCallingBack_stillCompletes() = runBlocking<Unit> {
        // A scope of the test's own, so a call that never resumes cannot hold up runBlocking.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val extraction = scope.async {
                recognizeText(ImageBitmap(8, 8)) { _, _ -> NSError.errorWithDomain("VNErrorDomain", 9, null) }
            }

            val outcome = withTimeoutOrNull(TIMEOUT_MS) { runCatching { extraction.await() } }

            assertNotNull(outcome, "one-shot OCR never completed after Vision failed without calling back")
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}

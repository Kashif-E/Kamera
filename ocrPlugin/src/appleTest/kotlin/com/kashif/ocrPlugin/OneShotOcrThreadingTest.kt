package com.kashif.ocrPlugin

import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
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
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * One-shot OCR never runs Vision on the caller's thread, which is Main for a Compose caller, and it
 * always completes, with a Failure, even when Vision fails without calling its completion handler.
 */
class OneShotOcrThreadingTest {

    @Test
    fun vision_runsOffTheCallersThread() = withPng { path ->
        runBlocking<Unit> {
            val caller = NSThread.currentThread
            val visionThread = CompletableDeferred<NSThread>()
            val plugin = plugin { _, _, request ->
                visionThread.complete(NSThread.currentThread)
                request.completionHandler?.invoke(request, null)
                null
            }

            val result = withTimeout(TIMEOUT_MS) { plugin.recognize(capture(path)) }

            assertEquals("", assertIs<CameraKResult.Success<RecognizedText>>(result).value.text)
            // == is isEqual, which compares the NSThreads themselves; two Kotlin references to one
            // NSThread need not be the same object.
            assertNotEquals(caller, visionThread.await(), "Vision ran on the thread that called recognize")
        }
    }

    @Test
    fun visionFailingWithoutCallingBack_stillCompletes() = withPng { path ->
        runBlocking<Unit> {
            // A scope of the test's own, so a call that never resumes cannot hold up runBlocking.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val plugin = plugin { _, _, _ -> NSError.errorWithDomain("VNErrorDomain", 9, null) }
                val extraction = scope.async { plugin.recognize(capture(path)) }

                val outcome = withTimeoutOrNull(TIMEOUT_MS) { extraction.await() }

                assertNotNull(outcome, "one-shot OCR never completed after Vision failed without calling back")
                assertIs<CameraKError.Internal>(assertIs<CameraKResult.Failure>(outcome).error)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun visionReturningWithoutCallingBack_stillCompletes() = withPng { path ->
        runBlocking<Unit> {
            // performRequests can return false with no NSError, or return without running the handler.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val plugin = plugin { _, _, _ -> null }
                val extraction = scope.async { plugin.recognize(capture(path)) }

                val outcome = withTimeoutOrNull(TIMEOUT_MS) { extraction.await() }

                assertNotNull(outcome, "one-shot OCR never completed after Vision returned without calling back")
                assertIs<CameraKError.Internal>(assertIs<CameraKResult.Failure>(outcome).error)
            } finally {
                scope.cancel()
            }
        }
    }

    private fun plugin(performer: VisionPerformer) =
        OcrPlugin(OcrConfig(), AppleTextRecognizers(stillPerformer = performer))

    private fun withPng(test: (String) -> Unit) {
        val path = plainPng()
        try {
            test(path)
        } finally {
            deleteFile(path)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}

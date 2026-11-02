package com.kashif.cameraK.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class SuspendRunCatchingTest {
    @Test
    fun cancellationIsRethrown() = runTest {
        val cancelled = CancellationException("the caller left")
        val thrown = assertFailsWith<CancellationException> { suspendRunCatching<Unit> { throw cancelled } }
        assertSame(cancelled, thrown, "cancellation must leave unchanged, never become a failure")
    }

    @Test
    fun ordinaryExceptionBecomesAFailure() = runTest {
        val failure = IllegalStateException("platform failure")
        val result = suspendRunCatching<Int> { throw failure }
        assertSame(failure, result.exceptionOrNull())
    }

    @Test
    fun valueBecomesASuccess() = runTest {
        assertEquals(Result.success(4), suspendRunCatching { 2 + 2 })
    }
}

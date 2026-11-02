package com.kashif.cameraK.result

import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class CameraKResultTest {
    private val busy = CameraKError.Busy(CameraOperation.CAPTURE)
    private val success: CameraKResult<Int> = CameraKResult.Success(2)
    private val failure: CameraKResult<Int> = CameraKResult.Failure(busy)

    @Test
    fun foldMapGetOrElseOnSuccessAndFailure() {
        assertEquals("value 2", success.fold(onSuccess = { "value $it" }, onFailure = { "error $it" }))
        assertEquals("error", failure.fold(onSuccess = { "value $it" }, onFailure = { "error" }))

        assertEquals(CameraKResult.Success(3), success.map { it + 1 })
        assertSame(busy, assertIsFailure(failure.map { it + 1 }).error, "map changed a failure's error")

        assertEquals(2, success.getOrElse { -1 })
        assertEquals(-1, failure.getOrElse { if (it === busy) -1 else -2 })
    }

    @Test
    fun getOrNullErrorOrNullAndCallbacksPickTheirSide() {
        assertEquals(2, success.getOrNull())
        assertNull(failure.getOrNull())
        assertNull(success.errorOrNull)
        assertSame(busy, failure.errorOrNull)

        val seen = mutableListOf<String>()
        success.onSuccess { seen += "success $it" }.onFailure { seen += "failure" }
        failure.onSuccess { seen += "success $it" }.onFailure { seen += "failure" }
        assertEquals(listOf("success 2", "failure"), seen)
        assertSame(success, success.onSuccess {}, "onSuccess must return the same result")
        assertSame(failure, failure.onFailure {}, "onFailure must return the same result")
    }

    @Test
    fun successAndFailureHaveValueEquality() {
        assertEquals(CameraKResult.Success("a"), CameraKResult.Success("a"))
        assertEquals(CameraKResult.Success("a").hashCode(), CameraKResult.Success("a").hashCode())
        assertNotEquals<CameraKResult<String>>(CameraKResult.Success("a"), CameraKResult.Success("b"))

        assertEquals(CameraKResult.Failure(CameraKError.Closed), CameraKResult.Failure(CameraKError.Closed))
        assertEquals(
            CameraKResult.Failure(CameraKError.Closed).hashCode(),
            CameraKResult.Failure(CameraKError.Closed).hashCode(),
        )
        assertNotEquals(CameraKResult.Failure(CameraKError.Closed), CameraKResult.Failure(CameraKError.NotReady))
        assertEquals("Success(value=a)", CameraKResult.Success("a").toString())
    }

    private fun <T> assertIsFailure(result: CameraKResult<T>): CameraKResult.Failure =
        result as? CameraKResult.Failure ?: throw AssertionError("expected a failure, got $result")
}

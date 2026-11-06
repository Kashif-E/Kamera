@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.state

import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/** The holder's one mapping from the engine's state to its own (design §2.2 table). */
class EngineStateMappingTest {
    private val ready = CameraKState.Ready
    private val failure = CameraKError.Disconnected()
    private val holderStates: List<CameraKState> =
        listOf(
            CameraKState.Idle,
            CameraKState.Starting,
            ready,
            CameraKState.Interrupted(InterruptionReason.OTHER),
            CameraKState.Error(CameraKError.CameraInUse()),
        )
    private val engineStates: List<EngineState> =
        listOf(
            EngineState.Closed,
            EngineState.Opening,
            EngineState.Open,
            EngineState.Interrupted(InterruptionReason.MULTITASKING),
            EngineState.Failed(failure),
        )

    @Test
    fun openMapsToReady() {
        holderStates.forEach { current ->
            assertSame(ready, EngineState.Open.toCameraKState(current), "Open over $current")
        }
    }

    @Test
    fun openInterruptedAndFailedMapToReadyInterruptedAndError() {
        assertSame(ready, EngineState.Open.toCameraKState(CameraKState.Starting))
        val interrupted = EngineState.Interrupted(InterruptionReason.MULTITASKING).toCameraKState(ready)
        assertEquals(InterruptionReason.MULTITASKING, assertIs<CameraKState.Interrupted>(interrupted).reason)
        val error = EngineState.Failed(failure).toCameraKState(ready)
        assertSame(failure, assertIs<CameraKState.Error>(error).error)
    }

    @Test
    fun closedAndOpeningLeaveTheCurrentStateUnchanged() {
        holderStates.forEach { current ->
            assertSame(current, EngineState.Closed.toCameraKState(current), "Closed over $current")
            assertSame(current, EngineState.Opening.toCameraKState(current), "Opening over $current")
        }
    }

    @Test
    fun everyEngineStateOverClosedStaysClosed() {
        engineStates.forEach { engine ->
            assertEquals(CameraKState.Closed, engine.toCameraKState(CameraKState.Closed), "$engine over Closed")
        }
    }
}

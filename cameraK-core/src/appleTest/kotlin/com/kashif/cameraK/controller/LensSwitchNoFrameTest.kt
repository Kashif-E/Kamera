@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.TraceNames
import com.kashif.cameraK.engine.Tracer
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.launch
import platform.Foundation.NSLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * With the frame output attached, a lens switch's `CameraK.lensSwitch` interval waits for the first
 * sample buffer from the new camera. A switch whose first frame never comes ends, marked
 * `CameraK.lensSwitch.noFrame`, at each of the engine's other ends: close, a failure and the next
 * switch (the stop is `LensSwitchSignpostTest`'s), and a stop that lands while the switch's block is
 * still committing ends it too, so no interval outlives the generation it began in (W7b).
 */
class LensSwitchNoFrameTest {
    /** Records each lens-switch interval by the order it began in, and every marker. */
    private class NumberedSwitchTracer : Tracer {
        private val lock = NSLock()
        private val recorded = mutableListOf<String>()
        private var begun = 0

        val events: List<String> get() = locked { recorded.toList() }

        override fun beginInterval(name: String): AutoCloseable {
            if (name != TraceNames.LENS_SWITCH) return AutoCloseable { }
            val number = locked {
                begun += 1
                recorded += "begin $begun"
                begun
            }
            return AutoCloseable { locked { recorded += "end $number" } }
        }

        override fun marker(name: String) = locked { recorded += "mark $name" }

        private fun <T> locked(block: () -> T): T {
            lock.lock()
            try {
                return block()
            } finally {
                lock.unlock()
            }
        }
    }

    private val tracer = NumberedSwitchTracer()
    private val driver = AppleEngineDriver(tracer = tracer)
    private val noFrame = "mark ${AppleTraceNames.LENS_SWITCH_NO_FRAME}"
    private val front = CameraSelection.ById(AppleEngineDriver.FRONT_ID)
    private val back = CameraSelection.ById(AppleEngineDriver.BACK_ID)

    @Test
    fun aSwitchWhoseFirstFrameNeverComesEndsAtCloseMarkedNoFrame() = driver.runCase {
        val engine = driver.createEngine(CameraConfiguration(frameAnalysis = true))
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

        assertIs<CameraKResult.Success<*>>(engine.selectCamera(front))
        assertEquals(listOf("begin 1"), tracer.events, "the interval ended before any frame from the new camera")
        engine.close()
        assertEquals(
            listOf("begin 1", noFrame, "end 1"),
            tracer.events,
            "a switch with no frame, at the engine's close",
        )
    }

    @Test
    fun aSwitchWhoseFirstFrameNeverComesEndsAtAFailureMarkedNoFrame() = driver.runCase {
        val engine = driver.createEngine(CameraConfiguration(frameAnalysis = true))
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

        assertIs<CameraKResult.Success<*>>(engine.selectCamera(front))
        assertEquals(listOf("begin 1"), tracer.events, "the interval ended before any frame from the new camera")
        driver.fail()
        driver.settle()
        assertIs<EngineState.Failed>(engine.state.value, "the engine after a disconnected camera")
        assertEquals(listOf("begin 1", noFrame, "end 1"), tracer.events, "a switch with no frame, at the failure")
        engine.close()
        assertEquals(listOf("begin 1", noFrame, "end 1"), tracer.events, "the close ended the interval again")
    }

    @Test
    fun aSwitchWhoseFirstFrameNeverComesEndsAtTheNextSwitchMarkedNoFrame() = driver.runCase {
        val engine = driver.createEngine(CameraConfiguration(frameAnalysis = true))
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

        assertIs<CameraKResult.Success<*>>(engine.selectCamera(front))
        assertIs<CameraKResult.Success<*>>(engine.selectCamera(back))
        assertEquals(
            listOf("begin 1", noFrame, "end 1", "begin 2"),
            tracer.events,
            "the first switch with no frame, when the next switch began",
        )
        assertTrue(driver.deliverFrame(driver.back.input), "no frame output took the back camera's buffer")
        assertEquals(
            listOf("begin 1", noFrame, "end 1", "begin 2", "end 2"),
            tracer.events,
            "the second switch, at its first frame",
        )
        engine.close()
    }

    @Test
    fun aSwitchRequestedBeforeThePreviousOneCommittedEndsItMarkedNoFrame() = driver.runCase {
        val engine = driver.createEngine(CameraConfiguration(frameAnalysis = true))
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

        // Both switches are requested while the session queue is busy, so neither block has run yet.
        val released = atomic(false)
        driver.custom.afterQueuedSessionWork { spinUntil(10.0) { released.value } }
        val first = launch { assertIs<CameraKResult.Success<*>>(engine.selectCamera(front)) }
        val second = launch { assertIs<CameraKResult.Success<*>>(engine.selectCamera(back)) }
        testScheduler.runCurrent()
        assertEquals(listOf("begin 1", "begin 2"), tracer.events, "both switches requested, neither committed")
        released.value = true
        driver.settle()
        first.join()
        second.join()
        assertEquals(
            listOf("begin 1", "begin 2", noFrame, "end 1"),
            tracer.events,
            "the first switch with no frame, once the second committed",
        )
        assertTrue(driver.deliverFrame(driver.back.input), "no frame output took the back camera's buffer")
        assertEquals(
            listOf("begin 1", "begin 2", noFrame, "end 1", "end 2"),
            tracer.events,
            "the second switch, at its first frame",
        )
        engine.close()
    }

    @Test
    fun aStopBetweenTheSwitchsLiveCheckAndItsCommitEndsTheSwitch() = driver.runCase {
        driver.createEngine(CameraConfiguration(frameAnalysis = true))
        val engine = driver.engine
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

        val inGap = atomic(false)
        val stopCut = atomic(false)
        engine.afterSwitchLiveCheck = {
            inGap.value = true
            check(spinUntil(10.0) { stopCut.value }) { "the stop's cut never came" }
        }
        // The stop's recording step goes first on the session queue; the switch's block runs next, and
        // the stop's cut lands on the test thread while that block waits after its live check.
        val stopping = launch { engine.stop() }
        testScheduler.runCurrent()
        engine.switchCamera(front) { }
        awaitTrue("the switch's live check") { inGap.value }
        testScheduler.runCurrent()
        assertEquals(EngineState.Closed, engine.state.value, "the stop's cut, inside the switch's gap")
        stopCut.value = true
        driver.settle()
        stopping.join()
        assertEquals(
            listOf("begin 1", noFrame, "end 1"),
            tracer.events,
            "a switch whose live check came before the stop, once the stop returned",
        )
        engine.close()
    }
}

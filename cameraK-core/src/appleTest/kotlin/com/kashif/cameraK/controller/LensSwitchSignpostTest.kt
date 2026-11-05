@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.TraceNames
import com.kashif.cameraK.engine.Tracer
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import platform.Foundation.NSLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A lens switch is one `CameraK.lensSwitch` interval, from the engine's selectCamera to the new input
 * live: it ends after the swap's commitConfiguration, with the session running on the new camera, and
 * before selectCamera returns, when no video data output is attached. With the frame output attached
 * (I4') the interval ends at the first sample buffer whose input is the new camera's, and a switch
 * whose first frame never comes ends at the engine's stop, marked noFrame. A refused input ends the
 * interval too, marked failed, so none is left open.
 */
class LensSwitchSignpostTest {
    /** Records the lens-switch events, and at each interval's end what the session then had. */
    private class SwitchTracer : Tracer {
        private val lock = NSLock()
        private val recorded = mutableListOf<String>()
        var atEnd: () -> String = { "" }

        val events: List<String> get() = locked { recorded.toList() }

        override fun beginInterval(name: String): AutoCloseable {
            if (name != TraceNames.LENS_SWITCH) return AutoCloseable { }
            locked { recorded += "begin" }
            return AutoCloseable {
                val state = atEnd()
                locked { recorded += "end $state" }
            }
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

    private val tracer = SwitchTracer()
    private val driver = AppleEngineDriver(tracer = tracer, adjustableBack = true)

    @Test
    fun intervalRunsFromSelectCameraToTheNewInputLive() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        tracer.atEnd = {
            val camera = when (driver.session.inputs) {
                listOf(driver.front.input) -> "front"
                listOf(driver.back.input) -> "back"
                else -> "other"
            }
            "$camera${if (driver.session.isRunning) " running" else " stopped"}"
        }

        assertIs<CameraKResult.Success<*>>(engine.selectCamera(CameraSelection.ById(AppleEngineDriver.FRONT_ID)))
        assertEquals(
            listOf("begin", "end front running"),
            tracer.events,
            "the lens-switch interval when selectCamera returned",
        )

        driver.session.refuse(driver.back.input!!)
        assertIs<CameraKResult.Failure>(engine.selectCamera(CameraSelection.ById(AppleEngineDriver.BACK_ID)))
        assertEquals(
            listOf(
                "begin",
                "end front running",
                "begin",
                "mark ${AppleTraceNames.LENS_SWITCH_FAILED}",
                "end front running",
            ),
            tracer.events,
            "a refused switch's interval when selectCamera returned",
        )
        engine.close()
    }

    @Test
    fun intervalEndsAtTheFirstSampleBufferFromTheNewDevice() = driver.runCase {
        val engine = driver.createEngine(CameraConfiguration(frameAnalysis = true))
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        tracer.atEnd = { "at a frame" }

        assertIs<CameraKResult.Success<*>>(engine.selectCamera(CameraSelection.ById(AppleEngineDriver.FRONT_ID)))
        assertEquals(listOf("begin"), tracer.events, "the interval ended before any frame from the new camera")
        assertTrue(driver.deliverFrame(driver.back.input), "no frame output took the previous camera's buffer")
        assertEquals(listOf("begin"), tracer.events, "a buffer still arriving from the previous camera ended it")
        assertTrue(driver.deliverFrame(driver.front.input), "no frame output took the new camera's buffer")
        assertEquals(listOf("begin", "end at a frame"), tracer.events, "the first buffer from the new camera")
        assertTrue(driver.deliverFrame(driver.front.input), "no frame output took the next buffer")
        assertEquals(listOf("begin", "end at a frame"), tracer.events, "a later buffer ended the interval again")
        engine.close()
    }

    @Test
    fun aSwitchWhoseFirstFrameNeverComesEndsAtStopMarkedNoFrame() = driver.runCase {
        val engine = driver.createEngine(CameraConfiguration(frameAnalysis = true))
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        tracer.atEnd = { "at stop" }

        assertIs<CameraKResult.Success<*>>(engine.selectCamera(CameraSelection.ById(AppleEngineDriver.FRONT_ID)))
        assertEquals(listOf("begin"), tracer.events, "the interval ended before any frame from the new camera")
        engine.stop()
        val ended = listOf("begin", "mark ${AppleTraceNames.LENS_SWITCH_NO_FRAME}", "end at stop")
        assertEquals(ended, tracer.events, "a switch with no frame from the new camera, at the engine's stop")
        engine.close()
        assertEquals(ended, tracer.events, "the close ended the interval again")
    }
}

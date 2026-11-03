@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.TraceNames
import com.kashif.cameraK.engine.Tracer
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import platform.Foundation.NSLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A lens switch is one `CameraK.lensSwitch` interval, from the engine's selectCamera to the new input
 * live: it ends after the swap's commitConfiguration, with the session running on the new camera, and
 * before selectCamera returns. The session has no video data output before I4', so the interval does
 * not wait for a frame. A refused input ends the interval too, marked failed, so none is left open.
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
}

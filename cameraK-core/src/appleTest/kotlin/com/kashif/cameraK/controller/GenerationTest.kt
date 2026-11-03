@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Every session notification is checked against the start generation on the session queue, so one
 * that belongs to a stopped or closed session changes nothing and does not restart the camera.
 */
class GenerationTest {
    private val driver = AppleEngineDriver()

    @Test
    fun interruptionEndedAfterStopLeavesEngineClosed() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        driver.interrupt(InterruptionReason.IN_USE_BY_ANOTHER_CLIENT)
        driver.settle()
        assertIs<EngineState.Interrupted>(engine.state.value)

        engine.stop()
        driver.settle()
        driver.endInterruption()
        driver.settle()

        assertEquals(EngineState.Closed, engine.state.value, "an interruption that ended after stop reopened")
        assertFalse(driver.session.isRunning, "the session runs after stop")
        engine.close()
    }

    @Test
    fun runtimeErrorAfterCloseIsDropped() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        val hardware = engine.hardware.value

        engine.close()
        driver.settle()
        driver.fail()
        driver.settle()

        assertEquals(EngineState.Closed, engine.state.value, "a runtime error after close changed the state")
        assertEquals(hardware, engine.hardware.value, "a runtime error after close changed the hardware")
    }

    @Test
    fun openArrivingAfterAStopThatCutTheStartShortStopsTheSessionAgain() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        driver.holdOpen()
        val start = async { engine.start(backCamera()) }
        driver.settle()

        engine.stop()
        driver.settle()
        assertEquals(CameraKResult.Failure(com.kashif.cameraK.error.CameraKError.Closed), start.await())

        driver.deliverOpen()
        driver.settle()

        assertEquals(EngineState.Closed, engine.state.value)
        assertFalse(driver.session.isRunning, "the late open left the camera running after stop")
        engine.close()
    }
}

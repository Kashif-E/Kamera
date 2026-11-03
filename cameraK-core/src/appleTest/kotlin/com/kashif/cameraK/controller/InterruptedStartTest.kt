@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceInUseByAnotherClient
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A session AVFoundation leaves interrupted at startRunning (another app holds the camera) is a start
 * the platform accepted and holds: start succeeds with Interrupted and the reason AVFoundation gave,
 * and the session reaches Open on its own when the interruption ends.
 */
class InterruptedStartTest {
    private val driver = AppleEngineDriver()

    @Test
    fun sessionInterruptedAtStartReportsInterruptedThenOpen() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        driver.session.interruptNextStart(AVCaptureSessionInterruptionReasonVideoDeviceInUseByAnotherClient)

        val result = engine.start(backCamera())

        assertEquals(CameraKResult.Success(Unit), result, "a start the platform holds interrupted")
        assertEquals(
            EngineState.Interrupted(InterruptionReason.IN_USE_BY_ANOTHER_CLIENT),
            engine.state.value,
            "the state when start returned",
        )
        assertEquals(1, driver.session.startCount, "startRunning calls")

        driver.endInterruption()
        driver.settle()
        assertEquals(EngineState.Open, engine.state.value, "the session did not resume when the interruption ended")
        assertEquals(1, driver.session.startCount, "the engine started the session again instead of AVFoundation")
        engine.close()
    }
}

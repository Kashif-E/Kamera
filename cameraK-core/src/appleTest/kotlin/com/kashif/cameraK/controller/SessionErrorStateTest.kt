@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import platform.AVFoundation.AVErrorDeviceWasDisconnected
import platform.AVFoundation.AVErrorMediaServicesWereReset
import platform.AVFoundation.AVFoundationErrorDomain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A runtime error AVFoundation reports while the session runs reaches the engine's state instead of a
 * log line: Failed with the mapped error and AVFoundation's NSError as its cause. A media-services
 * reset interrupts the session and restarts it once.
 */
class SessionErrorStateTest {
    private val driver = AppleEngineDriver()

    @Test
    fun runtimeErrorReachesFailedState() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

        driver.session.fail(FakeSessionOps.avError(AVErrorDeviceWasDisconnected))
        driver.settle()

        val failed = assertIs<EngineState.Failed>(engine.state.value, "the state after a runtime error")
        val error = assertIs<CameraKError.Disconnected>(failed.error)
        val cause = assertIs<NSErrorException>(error.cause, "the runtime error lost AVFoundation's NSError")
        assertEquals(AVFoundationErrorDomain, cause.error.domain)
        assertEquals(AVErrorDeviceWasDisconnected, cause.error.code)
        engine.close()
    }

    @Test
    fun mediaServicesResetInterruptsAndRestartsTheSessionOnce() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        val states = Recorded(backgroundScope, engine.state)

        driver.session.fail(FakeSessionOps.avError(AVErrorMediaServicesWereReset))
        driver.settle()

        assertEquals(
            listOf(
                EngineState.Open,
                EngineState.Interrupted(InterruptionReason.MEDIA_SERVICES_RESET),
                EngineState.Open,
            ),
            states.values,
            "states through a media-services reset",
        )
        assertEquals(2, driver.session.startCount, "startRunning calls: the start and one restart")
        states.job.cancel()
        engine.close()
    }
}

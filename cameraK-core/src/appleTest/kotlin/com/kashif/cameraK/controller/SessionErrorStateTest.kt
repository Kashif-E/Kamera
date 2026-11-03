@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.START_TIMEOUT
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.advanceTimeBy
import platform.AVFoundation.AVErrorDeviceWasDisconnected
import platform.AVFoundation.AVErrorMediaServicesWereReset
import platform.AVFoundation.AVFoundationErrorDomain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A runtime error AVFoundation reports while the session runs reaches the engine's state instead of a
 * log line: Failed with the mapped error and AVFoundation's NSError as its cause. A media-services
 * reset interrupts the session and restarts it once; only a second failure in a row, a reset before
 * the restart ran or a restart that does not run within the start timeout, gives Failed(Hardware).
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

    @Test
    fun aResetAfterARestartThatRanRestartsAgain() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        val states = Recorded(backgroundScope, engine.state)

        driver.session.fail(FakeSessionOps.avError(AVErrorMediaServicesWereReset))
        driver.settle()
        driver.session.fail(FakeSessionOps.avError(AVErrorMediaServicesWereReset))
        driver.settle()

        val reset = EngineState.Interrupted(InterruptionReason.MEDIA_SERVICES_RESET)
        assertEquals(
            listOf(EngineState.Open, reset, EngineState.Open, reset, EngineState.Open),
            states.values,
            "states through two resets with a restart that ran between them",
        )
        assertEquals(3, driver.session.startCount, "startRunning calls: the start and one restart per reset")
        states.job.cancel()
        engine.close()
    }

    @Test
    fun aRestartThatDoesNotRunEndsFailedHardwareWithinTheStartTimeout() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

        // The restart returns with the session neither running nor interrupted, and nothing reports it later.
        driver.session.holdNextStart()
        driver.session.fail(FakeSessionOps.avError(AVErrorMediaServicesWereReset))
        driver.settle()
        assertEquals(
            EngineState.Interrupted(InterruptionReason.MEDIA_SERVICES_RESET),
            engine.state.value,
            "the state while the restart is pending",
        )

        advanceTimeBy(START_TIMEOUT)
        driver.settle()

        val failed = assertIs<EngineState.Failed>(engine.state.value, "the state once the start timeout passed")
        assertIs<CameraKError.Hardware>(failed.error, "a restart that never ran")
        engine.close()
    }
}

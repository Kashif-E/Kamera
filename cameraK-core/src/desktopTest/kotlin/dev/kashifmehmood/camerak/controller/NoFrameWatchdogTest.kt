@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.engine.BackoffPolicy
import dev.kashifmehmood.camerak.engine.CameraSelection
import dev.kashifmehmood.camerak.engine.DesiredHardware
import dev.kashifmehmood.camerak.engine.EngineState
import dev.kashifmehmood.camerak.enums.CameraDeviceType
import dev.kashifmehmood.camerak.enums.CameraLens
import dev.kashifmehmood.camerak.enums.FlashMode
import dev.kashifmehmood.camerak.enums.TorchMode
import dev.kashifmehmood.camerak.error.CameraKError
import dev.kashifmehmood.camerak.error.InterruptionReason
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A camera that stops delivering frames after Open (unplugged, or a driver that stalls) is seen by
 * the no-frame watchdog: the engine reports Interrupted(OTHER) while it reopens with backoff, and
 * Failed(Disconnected) once the camera has not come back within the recovery timeout. A camera
 * that comes back while the engine reopens is Open again with no new start.
 */
class NoFrameWatchdogTest {
    private val desired = DesiredHardware(
        selection = CameraSelection.ByLens(CameraLens.BACK, CameraDeviceType.DEFAULT),
        flashMode = FlashMode.OFF,
        torchMode = TorchMode.OFF,
        zoomRatio = 1f,
        targetOrientation = null,
    )

    @Test
    fun noFramesAfterOpenGivesInterruptedThenDisconnected() = runTest {
        val rig = DesktopEngineRig(testScheduler, backoff = BackoffPolicy.Default)
        val sut = rig.engine()
        val states = CopyOnWriteArrayList<EngineState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { sut.state.collect { states += it } }
        try {
            val start = async { sut.start(desired) }
            rig.settle()
            assertEquals(CameraKResult.Success(Unit), start.await())
            // The camera delivered its first frame and nothing after it, also on every reopen.
            rig.camera.holdOpen = true

            advanceTimeBy(NO_FRAME_TIMEOUT - 1.milliseconds)
            rig.settle()
            assertEquals(EngineState.Open, sut.state.value, "interrupted before the no-frame timeout")

            advanceTimeBy(1.milliseconds)
            runCurrent()
            rig.settle()
            assertEquals(EngineState.Interrupted(InterruptionReason.OTHER), sut.state.value)

            advanceTimeBy(BackoffPolicy.Default.delayBefore(1))
            runCurrent()
            rig.settle()
            assertTrue(rig.camera.startCount > 1, "the engine did not reopen the camera while interrupted")
            assertEquals(EngineState.Interrupted(InterruptionReason.OTHER), sut.state.value)

            advanceTimeBy(RECOVERY_TIMEOUT)
            runCurrent()
            rig.settle()
            val failed = assertIs<EngineState.Failed>(sut.state.value)
            assertIs<CameraKError.Disconnected>(failed.error)
            assertEquals(
                listOf(
                    EngineState.Closed,
                    EngineState.Opening,
                    EngineState.Open,
                    EngineState.Interrupted(InterruptionReason.OTHER),
                    failed,
                ),
                states.distinct(),
            )
            assertEquals(rig.camera.startCount, rig.camera.releaseCount, "a reopened camera was left open")
        } finally {
            sut.close()
        }
    }

    @Test
    fun cameraThatComesBackWhileReopeningIsOpenAgainWithoutANewStart() = runTest {
        val rig = DesktopEngineRig(testScheduler, backoff = BackoffPolicy.Default)
        val sut = rig.engine()
        try {
            val start = async { sut.start(desired) }
            rig.settle()
            assertEquals(CameraKResult.Success(Unit), start.await())
            rig.camera.holdOpen = true

            advanceTimeBy(NO_FRAME_TIMEOUT)
            runCurrent()
            rig.settle()
            assertEquals(EngineState.Interrupted(InterruptionReason.OTHER), sut.state.value)

            // The first reopen waits out the backoff, then the camera delivers again.
            advanceTimeBy(BackoffPolicy.Default.delayBefore(1))
            runCurrent()
            rig.settle()
            rig.camera.deliverFrame()
            rig.settle()

            assertEquals(EngineState.Open, sut.state.value, "the camera came back but the engine stayed interrupted")
        } finally {
            sut.close()
        }
    }

    @Test
    fun grabFailureAfterOpenTakesTheSamePath() = runTest {
        val rig = DesktopEngineRig(testScheduler, backoff = BackoffPolicy.Default)
        val sut = rig.engine()
        try {
            val start = async { sut.start(desired) }
            rig.settle()
            assertEquals(CameraKResult.Success(Unit), start.await())
            rig.camera.holdOpen = true

            rig.camera.failGrab(IOException("the device went away"))
            rig.settle()
            assertEquals(EngineState.Interrupted(InterruptionReason.OTHER), sut.state.value)

            advanceTimeBy(RECOVERY_TIMEOUT)
            runCurrent()
            rig.settle()
            assertIs<CameraKError.Disconnected>(assertIs<EngineState.Failed>(sut.state.value).error)
        } finally {
            sut.close()
        }
    }
}

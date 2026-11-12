@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.controller.AndroidCameraEngine
import com.kashif.cameraK.controller.FakeMetering
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.CONTRACT_CAMERAS
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.desired
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exposure compensation on CameraX (w13 Z-focus-android). The capabilities give each bound camera's
 * `ExposureState` range and step, and FOCUS_POINT where it meters at a point. `setExposureCompensation`
 * awaits CameraX and publishes the index `ExposureState` reads back, never the request (rule 5); a newer
 * request supersedes a pending one with Busy. The last successful index is re-applied after every bind,
 * camera switch and restart, clamped into the camera's range, and dropped to 0 on a camera without
 * one (rule 6). The reset cancels metering and returns the index to 0.
 */
@RunWith(RobolectricTestRunner::class)
class ExposureCompensationTest {
    private val platform = FakeAndroidPlatform(CONTRACT_CAMERAS, meteringOf = FakeAndroidPlatform.CONTRACT_METERING)

    @Test
    fun theBoundCamerasRangeStepAndFocusPointAreInTheCapabilities() = runTest {
        val engine = open()

        val capabilities = engine.capabilities.value
        assertEquals(-12..12, capabilities.exposureCompensationRange(BACK.id))
        assertEquals(1f / 6, capabilities.exposureCompensationStep(BACK.id))
        assertTrue(capabilities.supportsFocusPoint(BACK.id))
        engine.closeAndJoin()
    }

    @Test
    fun aCameraWithoutCompensationHasNoRangeOrStep() = runTest {
        val platform = FakeAndroidPlatform(CONTRACT_CAMERAS)
        val engine = platform.engine(testScheduler)
        open(platform, engine)

        val capabilities = engine.capabilities.value
        assertNull(capabilities.exposureCompensationRange(BACK.id))
        assertNull(capabilities.exposureCompensationStep(BACK.id))
        val error = assertIs<CameraKError.Unsupported>(failure(engine.setExposureCompensation(1)))
        assertEquals(CameraFeature.EXPOSURE_COMPENSATION, error.feature)
        assertTrue(platform.camera!!.metering.exposureRequests.isEmpty())
        engine.closeAndJoin()
    }

    @Test
    fun hardwarePublishesTheIndexCameraXReadsBackOnceTheRequestCompletes() = runTest {
        val engine = open()
        val metering = platform.camera!!.metering
        // A device that applies one index below what it is asked for: what is published is what it applied.
        metering.applies = { it - 1 }
        metering.holdExposure = true

        val set = async { engine.setExposureCompensation(4) }
        platform.settle(testScheduler)
        assertEquals(listOf(4), metering.exposureRequests, "the index did not reach CameraX")
        assertFalse(set.isCompleted, "setExposureCompensation returned before CameraX completed the request")
        assertEquals(0, engine.hardware.value.exposureCompensationIndex, "hardware moved before CameraX completed")
        metering.completeExposure()
        platform.settle(testScheduler)

        assertEquals(CameraKResult.Success(Unit), set.await())
        assertEquals(3, engine.hardware.value.exposureCompensationIndex, "hardware is ExposureState's read-back")
        engine.closeAndJoin()
    }

    @Test
    fun aNewerRequestSupersedesThePendingOneWithBusy() = runTest {
        val engine = open()
        val metering = platform.camera!!.metering
        metering.holdExposure = true

        val first = async { engine.setExposureCompensation(4) }
        platform.settle(testScheduler)
        val second = async { engine.setExposureCompensation(-2) }
        platform.settle(testScheduler)
        metering.completeExposure()
        platform.settle(testScheduler)

        val error = assertIs<CameraKError.Busy>(failure(first.await()))
        assertEquals(CameraOperation.EXPOSURE_COMPENSATION, error.operation)
        assertEquals(CameraKResult.Success(Unit), second.await())
        assertEquals(-2, engine.hardware.value.exposureCompensationIndex)
        engine.closeAndJoin()
    }

    // CameraX completes an exposure request only once auto-exposure settles (CONVERGED, LOCKED or
    // FLASH_REQUIRED). A Galaxy S23 in the dark searched for 6 to 44 frames at index 20 while every frame
    // already carried the index, and CameraX sets no limit on how long that search may last.
    @Test
    fun aRequestTheFramesReportAppliedSucceedsThoughAutoExposureHasNotSettled() = runTest {
        val engine = open()
        platform.camera!!.metering.holdExposure = true
        platform.backend.streams = false

        val set = async { engine.setExposureCompensation(4) }
        platform.settle(testScheduler)
        platform.backend.reportExposure(0)
        platform.settle(testScheduler)
        assertFalse(set.isCompleted, "a frame at the old index completed the request")
        platform.backend.reportExposure(4)
        platform.settle(testScheduler)

        assertTrue(set.isCompleted, "setExposureCompensation still waits once the frames carry the index")
        assertEquals(CameraKResult.Success(Unit), set.await())
        assertEquals(4, engine.hardware.value.exposureCompensationIndex)
        engine.closeAndJoin()
    }

    @Test
    fun aResetTheFramesReportAppliedSucceedsThoughAutoExposureHasNotSettled() = runTest {
        val engine = open()
        assertEquals(CameraKResult.Success(Unit), engine.setExposureCompensation(4))
        platform.camera!!.metering.holdExposure = true
        platform.backend.streams = false

        val reset = async { engine.resetFocusAndExposure() }
        platform.settle(testScheduler)
        platform.backend.reportExposure(4)
        platform.settle(testScheduler)
        assertFalse(reset.isCompleted, "a frame at the old index completed the reset")
        platform.backend.reportExposure(0)
        platform.settle(testScheduler)

        assertTrue(reset.isCompleted, "resetFocusAndExposure still waits once the frames carry index 0")
        assertEquals(CameraKResult.Success(Unit), reset.await())
        assertEquals(0, engine.hardware.value.exposureCompensationIndex)
        engine.closeAndJoin()
    }

    // CameraX drops an exposure request sent while it is still configuring the session it binds: on a Galaxy
    // S23 the re-apply of index 20 at a restart went out between two of CameraX's close-and-reopen cycles,
    // and every capture result after it carried 0 while ExposureState said 20 and CameraX never completed the
    // request. A session's first capture result shows it configured.
    @Test
    fun theIndexIsReappliedOnlyOnceTheRestartedSessionStreams() = runTest {
        val engine = open()
        assertEquals(CameraKResult.Success(Unit), engine.setExposureCompensation(5))
        engine.stop()
        platform.settle(testScheduler)
        val metering = platform.camera!!.metering
        platform.backend.streams = false

        assertEquals(CameraKResult.Success(Unit), engine.start(desired.withExposure(5)))
        platform.settle(testScheduler)
        assertEquals(listOf(5), metering.exposureRequests, "the index went out before the session streamed")
        platform.backend.reportExposure(0)
        platform.settle(testScheduler)

        assertEquals(listOf(5, 5), metering.exposureRequests, "the index once the session streamed")
        assertEquals(5, engine.hardware.value.exposureCompensationIndex)
        engine.closeAndJoin()
    }

    @Test
    fun aRequestGoesOutOnlyOnceTheSessionStreams() = runTest {
        val engine = platform.engine(testScheduler)
        platform.settle(testScheduler)
        platform.backend.streams = false
        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        platform.settle(testScheduler)
        val metering = platform.camera!!.metering

        val set = async { engine.setExposureCompensation(4) }
        platform.settle(testScheduler)
        assertTrue(metering.exposureRequests.isEmpty(), "the request went out before the session streamed")
        platform.backend.reportExposure(0)
        platform.settle(testScheduler)

        assertEquals(listOf(4), metering.exposureRequests)
        assertEquals(CameraKResult.Success(Unit), set.await())
        assertEquals(4, engine.hardware.value.exposureCompensationIndex)
        engine.closeAndJoin()
    }

    // A stop does not end the session at once: frames already in the pipeline still arrive, and a restart on the
    // same camera reuses the bound use cases, so it binds nothing. Those frames are not the new session's.
    @Test
    fun aFrameOfTheStoppedSessionDoesNotLetTheReapplyOutBeforeTheNewSessionStreams() = runTest {
        val engine = open()
        assertEquals(CameraKResult.Success(Unit), engine.setExposureCompensation(5))
        val stopped = platform.backend.currentSession()
        engine.stop()
        platform.settle(testScheduler)
        val metering = platform.camera!!.metering
        platform.backend.streams = false
        platform.backend.reportExposure(5, session = stopped)
        platform.settle(testScheduler)

        assertEquals(CameraKResult.Success(Unit), engine.start(desired.withExposure(5)))
        platform.settle(testScheduler)
        assertEquals(listOf(5), metering.exposureRequests, "the index went out on a frame of the stopped session")
        platform.backend.reportExposure(0)
        platform.settle(testScheduler)

        assertEquals(listOf(5, 5), metering.exposureRequests, "the index once the new session streamed")
        engine.closeAndJoin()
    }

    @Test
    fun aCommandSentBeforeTheSessionStreamsReplacesThePendingReapply() = runTest {
        val engine = platform.engine(testScheduler)
        platform.settle(testScheduler)
        platform.backend.streams = false
        assertEquals(CameraKResult.Success(Unit), engine.start(desired.withExposure(5)))
        platform.settle(testScheduler)
        val metering = platform.camera!!.metering

        val set = async { engine.setExposureCompensation(3) }
        platform.settle(testScheduler)
        platform.backend.reportExposure(0)
        platform.settle(testScheduler)

        assertEquals(CameraKResult.Success(Unit), set.await())
        assertEquals(listOf(3), metering.exposureRequests, "the re-apply went out after the command")
        assertEquals(3, engine.hardware.value.exposureCompensationIndex)
        engine.closeAndJoin()
    }

    @Test
    fun aResetThatACommandReplacedWhileItWaitedIsBusyAndSendsNothing() = runTest {
        val engine = platform.engine(testScheduler)
        platform.settle(testScheduler)
        platform.backend.streams = false
        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        platform.settle(testScheduler)
        val metering = platform.camera!!.metering

        val reset = async { engine.resetFocusAndExposure() }
        platform.settle(testScheduler)
        val set = async { engine.setExposureCompensation(4) }
        platform.settle(testScheduler)
        platform.backend.reportExposure(0)
        platform.settle(testScheduler)

        assertIs<CameraKError.Busy>(failure(reset.await()), "a reset the command replaced")
        assertEquals(CameraKResult.Success(Unit), set.await())
        assertEquals(listOf(4), metering.exposureRequests)
        engine.closeAndJoin()
    }

    @Test
    fun aFrameOfASessionStoppedBeforeItsFirstResultDoesNotLetTheReapplyOut() = runTest {
        val engine = platform.engine(testScheduler)
        platform.settle(testScheduler)
        platform.backend.streams = false
        assertEquals(CameraKResult.Success(Unit), engine.start(desired.withExposure(5)))
        platform.settle(testScheduler)
        val metering = platform.camera!!.metering
        val stopped = platform.backend.currentSession()
        engine.stop()
        platform.settle(testScheduler)
        platform.backend.reportExposure(0, session = stopped)
        platform.settle(testScheduler)

        assertEquals(CameraKResult.Success(Unit), engine.start(desired.withExposure(5)))
        platform.settle(testScheduler)
        assertTrue(metering.exposureRequests.isEmpty(), "the index went out on a frame of the stopped session")
        platform.backend.reportExposure(0)
        platform.settle(testScheduler)

        assertEquals(listOf(5), metering.exposureRequests, "the index once the new session streamed")
        engine.closeAndJoin()
    }

    @Test
    fun aCommandThatFailsLetsThePendingReapplyOut() = runTest {
        val engine = platform.engine(testScheduler)
        platform.settle(testScheduler)
        platform.backend.streams = false
        assertEquals(CameraKResult.Success(Unit), engine.start(desired.withExposure(5)))
        platform.settle(testScheduler)
        val metering = platform.camera!!.metering

        // Out of the camera's -12..12: CameraX fails it.
        val set = async { engine.setExposureCompensation(40) }
        platform.settle(testScheduler)
        platform.backend.reportExposure(0)
        platform.settle(testScheduler)

        assertIs<CameraKResult.Failure>(set.await())
        assertEquals(listOf(5), metering.exposureRequests, "the held index after the failed command")
        assertEquals(5, engine.hardware.value.exposureCompensationIndex)
        engine.closeAndJoin()
    }

    @Test
    fun theCameraClosingUnderAPendingRequestIsClosed() = runTest {
        val engine = open()
        platform.camera!!.metering.holdExposure = true

        val set = async { engine.setExposureCompensation(4) }
        platform.settle(testScheduler)
        engine.stop()
        platform.settle(testScheduler)

        assertIs<CameraKError.Closed>(failure(set.await()))
        engine.closeAndJoin()
    }

    @Test
    fun aStopUnderAPendingRequestIsClosedEvenWhenAStartOnAnotherCameraFollowsAtOnce() = runTest {
        val engine = open()
        val metering = platform.camera!!.metering
        metering.holdExposure = true
        // CameraX cancels the request on its own thread, after the restart has bound the front camera.
        metering.closeLeavesPending = true

        val set = async { engine.setExposureCompensation(4) }
        platform.settle(testScheduler)
        engine.stop()
        platform.settle(testScheduler)
        val front = DesiredHardware(
            CameraSelection.ByLens(CameraLens.FRONT, CameraDeviceType.DEFAULT),
            desired.flashMode,
            desired.torchMode,
            desired.zoomRatio,
            desired.targetOrientation,
            desired.exposureCompensationIndex,
        )
        assertEquals(CameraKResult.Success(Unit), engine.start(front))
        platform.settle(testScheduler)
        assertEquals(CameraLens.FRONT, engine.hardware.value.camera?.lens, "the restart bound the front camera")
        metering.failPending()
        platform.settle(testScheduler)

        assertIs<CameraKError.Closed>(failure(set.await()), "the stop cut the request short, not the start")
        engine.closeAndJoin()
    }

    @Test
    fun aRequestTheOldCameraAppliesAfterASwitchIsBusyAndLeavesTheHeldIndex() = runTest {
        val engine = open()
        assertEquals(CameraKResult.Success(Unit), engine.setExposureCompensation(2))
        val back = platform.camera!!.metering
        back.holdExposure = true
        back.closeLeavesPending = true

        val set = async { engine.setExposureCompensation(4) }
        platform.settle(testScheduler)
        select(engine, CameraLens.FRONT)
        assertEquals(listOf(2), platform.camera!!.metering.exposureRequests, "the held index on the front camera")
        // The old camera applies the request after the switch has re-applied the held index.
        back.completeExposure()
        platform.settle(testScheduler)

        val error = assertIs<CameraKError.Busy>(failure(set.await()), "a request the switch cut off")
        assertEquals(CameraOperation.EXPOSURE_COMPENSATION, error.operation)
        assertEquals(2, engine.hardware.value.exposureCompensationIndex, "the front camera's read-back")
        select(engine, CameraLens.BACK)
        assertEquals(listOf(2), platform.camera!!.metering.exposureRequests, "the held index after the late request")
        engine.closeAndJoin()
    }

    @Test
    fun aRequestTheCameraAppliesAfterAStopIsClosed() = runTest {
        val engine = open()
        val metering = platform.camera!!.metering
        metering.holdExposure = true
        metering.closeLeavesPending = true

        val set = async { engine.setExposureCompensation(4) }
        platform.settle(testScheduler)
        engine.stop()
        platform.settle(testScheduler)
        metering.completeExposure()
        platform.settle(testScheduler)

        assertIs<CameraKError.Closed>(failure(set.await()), "a request the stop cut off")
        engine.closeAndJoin()
    }

    @Test
    fun aResetTheOldCameraAppliesAfterASwitchIsBusyAndLeavesTheHeldIndex() = runTest {
        val engine = open()
        assertEquals(CameraKResult.Success(Unit), engine.setExposureCompensation(2))
        val back = platform.camera!!.metering
        back.holdExposure = true
        back.closeLeavesPending = true

        val reset = async { engine.resetFocusAndExposure() }
        platform.settle(testScheduler)
        select(engine, CameraLens.FRONT)
        back.completeExposure()
        platform.settle(testScheduler)

        val error = assertIs<CameraKError.Busy>(failure(reset.await()), "a reset the switch cut off")
        assertEquals(CameraOperation.FOCUS_POINT, error.operation)
        select(engine, CameraLens.BACK)
        assertEquals(listOf(2), platform.camera!!.metering.exposureRequests, "the held index after the late reset")
        engine.closeAndJoin()
    }

    @Test
    fun theIndexIsReappliedClampedIntoTheNextCamerasRange() = runTest {
        val engine = open()
        assertEquals(CameraKResult.Success(Unit), engine.setExposureCompensation(12))

        select(engine, CameraLens.FRONT)
        assertEquals(listOf(6), platform.camera!!.metering.exposureRequests, "12 clamped into the front camera's -6..6")
        assertEquals(6, engine.hardware.value.exposureCompensationIndex)

        select(engine, CameraLens.BACK)
        assertEquals(listOf(12), platform.camera!!.metering.exposureRequests, "the held index, not the clamped one")
        assertEquals(12, engine.hardware.value.exposureCompensationIndex)
        engine.closeAndJoin()
    }

    @Test
    fun aCameraWithoutARangeDropsTheIndexAndPublishesZero() = runTest {
        val platform = FakeAndroidPlatform(
            CONTRACT_CAMERAS,
            meteringOf = { if (it.lens == CameraLens.BACK) FakeMetering.FULL else FakeMetering() },
        )
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        assertEquals(CameraKResult.Success(Unit), engine.setExposureCompensation(5))
        assertEquals(5, engine.hardware.value.exposureCompensationIndex)

        select(engine, CameraLens.FRONT, platform)
        assertEquals(0, engine.hardware.value.exposureCompensationIndex, "a camera without compensation is at 0")
        select(engine, CameraLens.BACK, platform)

        assertTrue(platform.camera!!.metering.exposureRequests.isEmpty(), "the dropped index came back")
        assertEquals(0, engine.hardware.value.exposureCompensationIndex)
        engine.closeAndJoin()
    }

    @Test
    fun theIndexIsReappliedAfterARestart() = runTest {
        val engine = open()
        assertEquals(CameraKResult.Success(Unit), engine.setExposureCompensation(5))

        engine.stop()
        platform.settle(testScheduler)
        assertEquals(CameraKResult.Success(Unit), engine.start(desired.withExposure(5)))
        platform.settle(testScheduler)

        assertEquals(listOf(5, 5), platform.camera!!.metering.exposureRequests, "the index after the restart")
        assertEquals(5, engine.hardware.value.exposureCompensationIndex)
        engine.closeAndJoin()
    }

    @Test
    fun theConfiguredIndexReachesTheCameraWhenItOpens() = runTest {
        val engine = platform.engine(testScheduler)
        platform.settle(testScheduler)

        assertEquals(CameraKResult.Success(Unit), engine.start(desired.withExposure(-3)))
        platform.settle(testScheduler)

        assertEquals(listOf(-3), platform.camera!!.metering.exposureRequests)
        assertEquals(-3, engine.hardware.value.exposureCompensationIndex)
        engine.closeAndJoin()
    }

    @Test
    fun theResetCancelsMeteringAndReturnsTheIndexToZero() = runTest {
        val engine = open()
        assertEquals(CameraKResult.Success(Unit), engine.setExposureCompensation(5))

        assertEquals(CameraKResult.Success(Unit), engine.resetFocusAndExposure())

        val metering = platform.camera!!.metering
        assertEquals(1, metering.cancelCalls, "focus and metering was not cancelled")
        assertEquals(listOf(5, 0), metering.exposureRequests)
        assertEquals(0, engine.hardware.value.exposureCompensationIndex)
        select(engine, CameraLens.FRONT)
        assertTrue(platform.camera!!.metering.exposureRequests.isEmpty(), "the reset index was not the held one")
        engine.closeAndJoin()
    }

    @Test
    fun theResetSupersedesAPendingRequestWithBusy() = runTest {
        val engine = open()
        val metering = platform.camera!!.metering
        metering.holdExposure = true

        val set = async { engine.setExposureCompensation(4) }
        platform.settle(testScheduler)
        val reset = async { engine.resetFocusAndExposure() }
        platform.settle(testScheduler)
        metering.completeExposure()
        platform.settle(testScheduler)

        val error = assertIs<CameraKError.Busy>(failure(set.await()))
        assertEquals(CameraOperation.EXPOSURE_COMPENSATION, error.operation)
        assertEquals(CameraKResult.Success(Unit), reset.await())
        assertEquals(0, engine.hardware.value.exposureCompensationIndex)
        engine.closeAndJoin()
    }

    @Test
    fun theResetOnACameraWithNeitherIsUnsupported() = runTest {
        val platform = FakeAndroidPlatform(CONTRACT_CAMERAS)
        val engine = platform.engine(testScheduler)
        open(platform, engine)

        val error = assertIs<CameraKError.Unsupported>(failure(engine.resetFocusAndExposure()))

        assertEquals(CameraFeature.FOCUS_POINT, error.feature)
        assertEquals(0, platform.camera!!.metering.cancelCalls)
        engine.closeAndJoin()
    }

    private suspend fun TestScope.open(): AndroidCameraEngine =
        platform.engine(testScheduler).also { open(platform, it) }

    private suspend fun TestScope.select(
        engine: AndroidCameraEngine,
        lens: CameraLens,
        platform: FakeAndroidPlatform = this@ExposureCompensationTest.platform,
    ) {
        val selected = engine.selectCamera(CameraSelection.ByLens(lens, CameraDeviceType.DEFAULT))
        assertEquals(lens, assertIs<CameraKResult.Success<LensInfo>>(selected, "select the $lens camera").value.lens)
        platform.settle(testScheduler)
    }

    private fun failure(result: CameraKResult<*>): CameraKError = assertIs<CameraKResult.Failure>(result).error

    private fun DesiredHardware.withExposure(index: Int) =
        DesiredHardware(selection, flashMode, torchMode, zoomRatio, targetOrientation, index)

    private companion object {
        val BACK = CONTRACT_CAMERAS.single { it.lens == CameraLens.BACK }
    }
}

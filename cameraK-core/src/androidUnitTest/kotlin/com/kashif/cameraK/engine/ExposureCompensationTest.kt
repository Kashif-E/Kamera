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

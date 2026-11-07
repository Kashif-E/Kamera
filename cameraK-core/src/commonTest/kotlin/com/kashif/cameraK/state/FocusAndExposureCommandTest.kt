@file:OptIn(CameraKEngineApi::class, ExperimentalCoroutinesApi::class)

package com.kashif.cameraK.state

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.controls.NormalizedPoint
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.CameraEngineFactory
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.FakeCapabilities
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The holder's focus and exposure commands over [FakeCameraEngine] (w12 Y-focus-api, design §12.4
 * "W12"). With [FakeCapabilities.backAndFront]' defaults the back camera meters at a point and takes
 * exposure indexes -6 to 6; the front one has no focus point and takes -3 to 3.
 */
class FocusAndExposureCommandTest {
    private val success = CameraKResult.Success(Unit)

    private fun TestScope.holderOver(
        engine: CameraEngine,
        config: CameraConfiguration = CameraConfiguration(),
    ): CameraKStateHolder =
        CameraKStateHolder(config, CameraEngineFactory { _, _ -> engine }, emptyList(), cameraKTestEnvironment())

    private fun TestScope.readyHolder(
        engine: CameraEngine,
        config: CameraConfiguration = CameraConfiguration(),
    ): CameraKStateHolder = holderOver(engine, config).also {
        it.start()
        runCurrent()
        assertIs<CameraKState.Ready>(it.cameraState.value, "the holder did not reach Ready")
    }

    private fun withoutFocusOrExposure() = FakeCameraEngine(FakeCapabilities.backAndFront(focusAndExposure = false))

    private fun assertFailsWith(expected: CameraKError, result: CameraKResult<*>, what: String) {
        val error = assertIs<CameraKResult.Failure>(result, what).error
        assertEquals(expected::class, error::class, "$what: $error")
        when (expected) {
            is CameraKError.InvalidArgument ->
                assertEquals(expected.operation, (error as CameraKError.InvalidArgument).operation, what)
            is CameraKError.Unsupported ->
                assertEquals(expected.feature, (error as CameraKError.Unsupported).feature, what)
            else -> Unit
        }
    }

    private val focusOutOfRange = CameraKError.InvalidArgument(CameraOperation.FOCUS_POINT)
    private val exposureOutOfRange = CameraKError.InvalidArgument(CameraOperation.EXPOSURE_COMPENSATION)
    private val noFocusPoint = CameraKError.Unsupported(CameraFeature.FOCUS_POINT)
    private val noExposure = CameraKError.Unsupported(CameraFeature.EXPOSURE_COMPENSATION)

    @Test
    fun focusAtSendsThePointToTheEngine() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)

        assertEquals(success, holder.focusAt(NormalizedPoint(0.25f, 0.75f)))
        assertEquals(NormalizedPoint(0.25f, 0.75f), fake.lastFocusPoint)

        // The edges of the content rectangle are inside it.
        assertEquals(success, holder.focusAt(NormalizedPoint(0f, 1f)))
        assertEquals(success, holder.focusAt(NormalizedPoint(1f, 0f)))
        assertEquals(NormalizedPoint(1f, 0f), fake.lastFocusPoint)
    }

    @Test
    fun aFocusPointOutsideTheUnitSquareIsInvalidArgumentAndNeverReachesTheEngine() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)
        val outside = listOf(
            NormalizedPoint(-0.01f, 0.5f),
            NormalizedPoint(1.01f, 0.5f),
            NormalizedPoint(0.5f, -0.01f),
            NormalizedPoint(0.5f, 1.01f),
            NormalizedPoint(Float.NaN, 0.5f),
            NormalizedPoint(0.5f, Float.NaN),
            NormalizedPoint(Float.POSITIVE_INFINITY, 0.5f),
        )

        for (point in outside) assertFailsWith(focusOutOfRange, holder.focusAt(point), "focusAt($point)")
        assertEquals(emptyList(), fake.calls.filter { it == "focusAt" }, "an invalid point reached the engine")
        assertNull(fake.lastFocusPoint)
    }

    @Test
    fun focusAtOnACameraWithoutAFocusPointIsUnsupported() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)
        assertIs<CameraKResult.Success<LensInfo>>(holder.selectCamera(CameraLens.FRONT))

        assertFailsWith(noFocusPoint, holder.focusAt(NormalizedPoint(0.5f, 0.5f)), "focusAt on the front camera")
        assertNull(fake.lastFocusPoint, "the fixed-focus camera received a point")
        // A point outside the square is the caller's mistake on any camera.
        assertFailsWith(focusOutOfRange, holder.focusAt(NormalizedPoint(2f, 0.5f)), "focusAt(2, 0.5)")
    }

    @Test
    fun aFocusFailureFromThePlatformIsTheResult() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)

        fake.failNextFocus(CameraKError.Hardware(platformCode = 7))
        val failed = assertIs<CameraKResult.Failure>(holder.focusAt(NormalizedPoint(0.5f, 0.5f))).error
        assertEquals(7, assertIs<CameraKError.Hardware>(failed).platformCode)
        assertNull(fake.lastFocusPoint, "a failed focus reached the platform")

        assertEquals(success, holder.focusAt(NormalizedPoint(0.5f, 0.5f)), "the failure was for one call only")
    }

    @Test
    fun anExposureIndexInRangeReachesTheEngineAndUiStateFollowsThePlatform() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)

        assertEquals(success, holder.setExposureCompensation(4))
        assertEquals(4, fake.appliedExposureCompensationIndex, "the index the platform received")
        assertEquals(0, holder.uiState.value.exposureCompensationIndex, "uiState moved before the platform reported")

        fake.reportExposureIndex(4)
        runCurrent()
        assertEquals(4, holder.uiState.value.exposureCompensationIndex)

        // The ends of the range are in it.
        assertEquals(success, holder.setExposureCompensation(-6))
        assertEquals(success, holder.setExposureCompensation(6))
        assertEquals(6, fake.appliedExposureCompensationIndex)
    }

    @Test
    fun anExposureIndexOutsideTheRangeIsInvalidArgumentAndNeverReachesTheEngine() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)

        assertFailsWith(exposureOutOfRange, holder.setExposureCompensation(7), "setExposureCompensation(7)")
        assertFailsWith(exposureOutOfRange, holder.setExposureCompensation(-7), "setExposureCompensation(-7)")
        assertIs<CameraKResult.Success<LensInfo>>(holder.selectCamera(CameraLens.FRONT))
        assertFailsWith(exposureOutOfRange, holder.setExposureCompensation(4), "4 on the front camera's -3..3")

        assertEquals(emptyList(), fake.calls.filter { it == "setExposureCompensation" }, "an index reached the engine")
        assertEquals(0, fake.appliedExposureCompensationIndex)
    }

    @Test
    fun exposureOnACameraWithoutCompensationIsUnsupportedBeforeAnyRangeCheck() = runTest {
        val fake = withoutFocusOrExposure()
        val holder = readyHolder(fake)

        assertFailsWith(noExposure, holder.setExposureCompensation(0), "setExposureCompensation(0)")
        assertFailsWith(noExposure, holder.setExposureCompensation(99), "setExposureCompensation(99)")
        assertEquals(emptyList(), fake.calls.filter { it == "setExposureCompensation" }, "an index reached the engine")
    }

    @Test
    fun theHeldIndexIsClampedIntoTheNextCamerasRangeAndKeptForTheCameraAfter() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)
        assertEquals(success, holder.setExposureCompensation(5))

        assertIs<CameraKResult.Success<LensInfo>>(holder.selectCamera(CameraLens.FRONT))
        assertEquals(3, fake.appliedExposureCompensationIndex, "5 on the front camera's -3..3")

        assertIs<CameraKResult.Success<LensInfo>>(holder.selectCamera(CameraLens.BACK))
        assertEquals(5, fake.appliedExposureCompensationIndex, "the held index on the back camera again")
    }

    @Test
    fun theHeldIndexIsAppliedAgainAfterARestart() = runTest {
        val fake = FakeCameraEngine()
        val engine = StartsRecorded(fake)
        val holder = readyHolder(engine)
        assertEquals(success, holder.setExposureCompensation(-4))

        holder.stop()
        runCurrent()
        holder.start()
        runCurrent()

        assertIs<CameraKState.Ready>(holder.cameraState.value)
        assertEquals(-4, engine.starts.last().exposureCompensationIndex, "the restart's desired hardware")
        assertEquals(-4, fake.appliedExposureCompensationIndex, "the index the platform received on the restart")
    }

    @Test
    fun theConfiguredIndexIsClampedAtOpen() = runTest {
        val tooHigh = CameraConfiguration { initialExposureCompensationIndex = 9 }

        val back = FakeCameraEngine()
        readyHolder(back, tooHigh)
        assertEquals(6, back.appliedExposureCompensationIndex, "9 on the back camera's -6..6")

        val front = FakeCameraEngine()
        readyHolder(front, tooHigh.copy { cameraLens = CameraLens.FRONT })
        assertEquals(3, front.appliedExposureCompensationIndex, "9 on the front camera's -3..3")

        val none = withoutFocusOrExposure()
        readyHolder(none, tooHigh)
        assertEquals(0, none.appliedExposureCompensationIndex, "a camera without compensation ignores the index")
    }

    @Test
    fun resetReturnsToTheDefaultExposureAndDropsTheFocusPoint() = runTest {
        val fake = FakeCameraEngine()
        val engine = StartsRecorded(fake)
        val holder = readyHolder(engine)
        assertEquals(success, holder.setExposureCompensation(3))
        assertEquals(success, holder.focusAt(NormalizedPoint(0.1f, 0.2f)))

        assertEquals(success, holder.resetFocusAndExposure())
        assertEquals(0, fake.appliedExposureCompensationIndex)
        assertNull(fake.lastFocusPoint)

        holder.stop()
        runCurrent()
        holder.start()
        runCurrent()
        assertEquals(0, engine.starts.last().exposureCompensationIndex, "the reset index is the one held")
    }

    @Test
    fun resetSucceedsOnACameraWithEitherFeatureAndIsUnsupportedOnOneWithNeither() = runTest {
        val holder = readyHolder(FakeCameraEngine())
        assertIs<CameraKResult.Success<LensInfo>>(holder.selectCamera(CameraLens.FRONT))
        assertEquals(success, holder.resetFocusAndExposure(), "the front camera has exposure compensation only")

        val neither = readyHolder(withoutFocusOrExposure())
        assertFailsWith(noFocusPoint, neither.resetFocusAndExposure(), "resetFocusAndExposure on a camera with neither")
    }

    @Test
    fun theCommandsAreNotReadyBeforeReadyAndClosedAfterCloseWithoutReachingTheEngine() = runTest {
        val fake = FakeCameraEngine().apply { autoOpen = false }
        val holder = holderOver(fake)
        holder.start()
        runCurrent()
        assertEquals(CameraKState.Starting, holder.cameraState.value)

        assertFailsWith(CameraKError.NotReady, holder.focusAt(NormalizedPoint(0.5f, 0.5f)), "focusAt while starting")
        assertFailsWith(CameraKError.NotReady, holder.setExposureCompensation(1), "exposure while starting")
        assertFailsWith(CameraKError.NotReady, holder.resetFocusAndExposure(), "reset while starting")

        holder.closeAndJoin()
        assertFailsWith(CameraKError.Closed, holder.focusAt(NormalizedPoint(0.5f, 0.5f)), "focusAt after close")
        assertFailsWith(CameraKError.Closed, holder.focusAt(NormalizedPoint(5f, 0.5f)), "an invalid point after close")
        assertFailsWith(CameraKError.Closed, holder.setExposureCompensation(1), "exposure after close")
        assertFailsWith(CameraKError.Closed, holder.resetFocusAndExposure(), "reset after close")
        val commands = setOf("focusAt", "setExposureCompensation", "resetFocusAndExposure")
        assertTrue(fake.calls.none { it in commands }, "a command reached the engine: ${fake.calls}")
    }

    /** The fake, recording the desired hardware of every start. */
    private class StartsRecorded(private val fake: FakeCameraEngine) : CameraEngine by fake {
        val starts = mutableListOf<DesiredHardware>()

        override suspend fun start(desired: DesiredHardware): CameraKResult<Unit> {
            starts += desired
            return fake.start(desired)
        }
    }
}

@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.controls.NormalizedPoint
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import platform.AVFoundation.AVCaptureExposureModeContinuousAutoExposure
import platform.AVFoundation.AVCaptureFocusModeContinuousAutoFocus
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exposure compensation and the reset on the iOS engine (w13 Z-focus-ios), over fake devices whose
 * back camera takes indexes -6 to 6 and front camera -4 to 4, a third of an EV each.
 *
 * `setExposureCompensation` sets `exposureTargetBias` on the session queue under the configuration lock
 * and returns once its completion has run, with `hardware` already showing the index the device's bias
 * reads back as, never the request (rule 5). A newer call or a reset supersedes one in flight with
 * Busy(EXPOSURE_COMPENSATION); stop or close is Closed. The last index set is applied again after every
 * start and camera switch, clamped into the camera's range, and dropped on a camera without one (rule 6).
 * The reset returns to continuous focus and exposure at the centre with bias 0 and no subject-area
 * monitoring.
 */
class AppleExposureCompensationTest {
    private val driver = AppleEngineDriver(metering = true)

    @Test
    fun theBiasIsSetUnderTheLockAndHardwareShowsTheReadBack() = driver.runCase {
        val engine = started()
        // The device takes a bias of 0.6 EV for the 1/3 EV asked: index 2, not 1.
        driver.back.biasReadBack = { 0.6f }

        assertEquals(CameraKResult.Success(Unit), engine.setExposureCompensation(1))
        assertEquals(
            2,
            engine.hardware.value.exposureCompensationIndex,
            "hardware shows the request, not the read-back",
        )
        val set = driver.back.callsOf("setExposureTargetBias").single()
        assertClose(1f / 3, set.value as Float, "the bias of index 1")
        assertTrue(set.locked && set.queue == SESSION_QUEUE_LABEL, "$set")
        engine.closeAndJoin()
    }

    @Test
    fun hardwareWaitsForTheBiasCompletion() = driver.runCase {
        val engine = started()
        driver.back.holdBiasCompletions = true
        val set = async { engine.setExposureCompensation(3) }
        driver.settleUntil("the bias reaches the camera") { driver.back.lastBiasSet != null }
        driver.settle()
        assertTrue(!set.isCompleted, "setExposureCompensation returned before the bias completion")
        assertEquals(0, engine.hardware.value.exposureCompensationIndex, "hardware moved before the completion")

        driver.back.completeBiasChanges()
        driver.settleUntil("setExposureCompensation returns") { set.isCompleted }
        assertEquals(CameraKResult.Success(Unit), set.await())
        assertEquals(3, engine.hardware.value.exposureCompensationIndex)
        engine.closeAndJoin()
    }

    @Test
    fun aNewerCallSupersedesOneInFlight() = driver.runCase {
        val engine = started()
        driver.back.holdBiasCompletions = true
        val first = async { engine.setExposureCompensation(3) }
        driver.settleUntil("the first bias reaches the camera") { driver.back.lastBiasSet != null }
        val second = async { engine.setExposureCompensation(-2) }
        driver.settleUntil("the first call returns") { first.isCompleted }
        assertEquals(
            CameraOperation.EXPOSURE_COMPENSATION,
            assertIs<CameraKError.Busy>(failure(first.await())).operation,
        )

        driver.back.completeBiasChanges()
        driver.settleUntil("the second call returns") { second.isCompleted }
        assertEquals(CameraKResult.Success(Unit), second.await())
        assertEquals(-2, engine.hardware.value.exposureCompensationIndex)
        engine.closeAndJoin()
    }

    @Test
    fun aResetSupersedesACallInFlight() = driver.runCase {
        val engine = started()
        driver.back.holdBiasCompletions = true
        val set = async { engine.setExposureCompensation(3) }
        driver.settleUntil("the bias reaches the camera") { driver.back.lastBiasSet != null }
        val reset = async { engine.resetFocusAndExposure() }
        driver.settleUntil("the set returns") { set.isCompleted }
        assertEquals(CameraOperation.EXPOSURE_COMPENSATION, assertIs<CameraKError.Busy>(failure(set.await())).operation)

        driver.back.completeBiasChanges()
        driver.settleUntil("the reset returns") { reset.isCompleted }
        assertEquals(CameraKResult.Success(Unit), reset.await())
        assertEquals(0, engine.hardware.value.exposureCompensationIndex)
        engine.closeAndJoin()
    }

    @Test
    fun stopDuringACallAnswersClosed() = driver.runCase {
        val engine = started()
        driver.back.holdBiasCompletions = true
        val set = async { engine.setExposureCompensation(3) }
        driver.settleUntil("the bias reaches the camera") { driver.back.lastBiasSet != null }

        engine.stop()
        driver.settleUntil("the call returns") { set.isCompleted }
        assertSame(CameraKError.Closed, failure(set.await()))
        engine.closeAndJoin()
    }

    @Test
    fun closeDuringACallAnswersClosedAtOnce() = driver.runCase {
        val engine = started()
        driver.back.holdBiasCompletions = true
        val set = async { engine.setExposureCompensation(3) }
        driver.settleUntil("the bias reaches the camera") { driver.back.lastBiasSet != null }

        engine.close()
        driver.settleUntil("the call returns") { set.isCompleted }
        assertSame(CameraKError.Closed, failure(set.await()))
        engine.closeAndJoin()
    }

    @Test
    fun theHeldIndexIsClampedIntoTheNextCamerasRange() = driver.runCase {
        val engine = started()
        assertEquals(CameraKResult.Success(Unit), engine.setExposureCompensation(6))

        assertIs<CameraKResult.Success<*>>(engine.selectCamera(CameraSelection.ById(AppleEngineDriver.FRONT_ID)))
        driver.settle()
        assertClose(4f / 3, driver.front.lastBiasSet ?: 0f, "the front camera's bias, index 6 clamped to 4")
        assertEquals(emptyList(), driver.front.outOfRangeBiasSets, "a bias beyond the front camera's range")
        assertEquals(4, engine.hardware.value.exposureCompensationIndex)

        assertIs<CameraKResult.Success<*>>(engine.selectCamera(CameraSelection.ById(AppleEngineDriver.BACK_ID)))
        driver.settle()
        assertEquals(6, engine.hardware.value.exposureCompensationIndex, "the held index, not the clamped one")
        engine.closeAndJoin()
    }

    @Test
    fun aBoundJustShortOfAThirdOfAnEvIsNeverPassed() = driver.runCase {
        // Index 3 would set 1.0 EV, past the bound, and AVFoundation's exception would crash the app.
        driver.back.minExposureTargetBias = -0.9997f
        driver.back.maxExposureTargetBias = 0.9997f
        val engine = started()
        assertEquals(-2..2, engine.capabilities.value.exposureCompensationRange(AppleEngineDriver.BACK_ID))

        assertEquals(CameraKResult.Success(Unit), engine.setExposureCompensation(3))
        assertEquals(emptyList(), driver.back.outOfRangeBiasSets, "a bias past the device's bounds")
        assertClose(2f / 3, driver.back.lastBiasSet ?: 0f, "index 3 clamped to the range's 2")
        assertEquals(2, engine.hardware.value.exposureCompensationIndex)
        engine.closeAndJoin()
    }

    @Test
    fun theResetOnADeviceWhoseBiasExcludesZeroStaysInsideItsBounds() = driver.runCase {
        driver.back.minExposureTargetBias = 0.5f
        driver.back.maxExposureTargetBias = 1.5f
        val engine = started()
        assertEquals(2..4, engine.capabilities.value.exposureCompensationRange(AppleEngineDriver.BACK_ID))

        assertEquals(CameraKResult.Success(Unit), engine.resetFocusAndExposure())
        assertEquals(emptyList(), driver.back.outOfRangeBiasSets, "a bias past the device's bounds")
        assertClose(0.5f, driver.back.lastBiasSet ?: 0f, "the reset's bias 0, clamped to the lower bound")
        engine.closeAndJoin()
    }

    @Test
    fun aCameraWithoutARangeDropsTheIndex() = driver.runCase {
        driver.front.minExposureTargetBias = 0f
        driver.front.maxExposureTargetBias = 0f
        val engine = started()
        assertEquals(CameraKResult.Success(Unit), engine.setExposureCompensation(3))

        assertIs<CameraKResult.Success<*>>(engine.selectCamera(CameraSelection.ById(AppleEngineDriver.FRONT_ID)))
        driver.settle()
        assertEquals(null, driver.front.lastBiasSet, "a camera without compensation was given a bias")
        assertEquals(0, engine.hardware.value.exposureCompensationIndex)

        assertIs<CameraKResult.Success<*>>(engine.selectCamera(CameraSelection.ById(AppleEngineDriver.BACK_ID)))
        driver.settle()
        assertClose(0f, driver.back.lastBiasSet ?: 1f, "the dropped index on the back camera")
        assertEquals(0, engine.hardware.value.exposureCompensationIndex)
        engine.closeAndJoin()
    }

    @Test
    fun aStartAppliesTheDesiredIndex() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        val desired = backCamera()
        val withExposure = DesiredHardware(
            desired.selection,
            desired.flashMode,
            desired.torchMode,
            desired.zoomRatio,
            desired.targetOrientation,
            exposureCompensationIndex = 3,
        )
        assertEquals(CameraKResult.Success(Unit), engine.start(withExposure))
        driver.settle()
        assertClose(1f, driver.back.lastBiasSet ?: 0f, "the bias of index 3")
        assertEquals(3, engine.hardware.value.exposureCompensationIndex)

        engine.stop()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        driver.settle()
        assertClose(0f, driver.back.lastBiasSet ?: 1f, "a restart with index 0")
        engine.closeAndJoin()
    }

    @Test
    fun theResetMetersContinuouslyAtTheCentreWithBiasZero() = driver.runCase {
        val engine = started()
        assertEquals(CameraKResult.Success(Unit), engine.focusAt(NormalizedPoint(0.2f, 0.7f)))
        assertEquals(CameraKResult.Success(Unit), engine.setExposureCompensation(3))
        val before = driver.back.calls.size

        assertEquals(CameraKResult.Success(Unit), engine.resetFocusAndExposure())
        val calls = driver.back.calls.drop(before).filter { it.op in FOCUS_OPS }
        assertEquals(
            listOf(
                "setFocusPointOfInterest" to CENTRE_OF_INTEREST,
                "setFocusMode" to AVCaptureFocusModeContinuousAutoFocus,
                "setExposurePointOfInterest" to CENTRE_OF_INTEREST,
                "setExposureMode" to AVCaptureExposureModeContinuousAutoExposure,
                "setSubjectAreaChangeMonitoringEnabled" to false,
            ),
            calls.map { it.op to it.value },
        )
        for (call in calls) assertTrue(call.locked && call.queue == SESSION_QUEUE_LABEL, "$call")
        assertClose(0f, driver.back.lastBiasSet ?: 1f, "the reset's bias")
        assertEquals(0, engine.hardware.value.exposureCompensationIndex)
        assertEquals(0, driver.back.subjectAreaObserverCount, "the subject area is still observed")
        engine.closeAndJoin()
    }

    private suspend fun started(): CameraEngine {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        driver.settle()
        return engine
    }

    private fun assertClose(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) < 1e-5f, "$what: expected $expected, was $actual")
}

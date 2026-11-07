@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.controls.NormalizedPoint
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import platform.AVFoundation.AVCaptureExposureModeAutoExpose
import platform.AVFoundation.AVCaptureExposureModeContinuousAutoExposure
import platform.AVFoundation.AVCaptureFocusModeAutoFocus
import platform.AVFoundation.AVCaptureFocusModeContinuousAutoFocus
import platform.Foundation.NSError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `focusAt` on the iOS engine (w13 Z-focus-ios), over fake devices: the back camera focuses and meters
 * exposure at a point, the front camera meters exposure only.
 *
 * Every device call runs on the session queue under the configuration lock. The focus is settled when
 * `isAdjustingFocus` turns false after having been true, or is false 150 ms after the set: Success
 * within 2 s, Timeout(FOCUS_POINT) after. A camera that meters exposure only succeeds once the
 * configuration is committed. A newer focusAt, a reset or a camera switch during the wait is
 * Busy(FOCUS_POINT); stop or close is Closed; a refused configuration lock is Hardware with the error's
 * code. A subject-area change returns to continuous metering at the centre.
 */
class AppleFocusAtTest {
    private val driver = AppleEngineDriver(metering = true)

    @Test
    fun focusAtSetsThePointAndAutoModesUnderTheLockOnTheSessionQueue() = driver.runCase {
        val engine = started()
        assertEquals(CameraKResult.Success(Unit), engine.focusAt(NormalizedPoint(0.5f, 0.5f)))

        val calls = driver.back.calls.filter { it.op in FOCUS_OPS }
        assertEquals(
            listOf(
                "setFocusPointOfInterest" to CENTRE_OF_INTEREST,
                "setFocusMode" to AVCaptureFocusModeAutoFocus,
                "setExposurePointOfInterest" to CENTRE_OF_INTEREST,
                "setExposureMode" to AVCaptureExposureModeAutoExpose,
                "setSubjectAreaChangeMonitoringEnabled" to true,
            ),
            calls.map { it.op to it.value },
        )
        for (call in calls) {
            assertTrue(call.locked && call.queue == SESSION_QUEUE_LABEL, "$call")
        }
        assertEquals(1, driver.back.subjectAreaObserverCount, "the subject area is not observed")
        engine.closeAndJoin()
    }

    @Test
    fun aFocusStillAdjustingAfter150MsSucceedsWhenItSettles() = driver.runCase {
        val engine = started()
        driver.back.adjustFocus(true)
        val focus = async { engine.focusAt(NormalizedPoint(0.3f, 0.3f)) }
        settleUntil("the focus point reaches the camera") {
            driver.back.callsOf("setFocusPointOfInterest").isNotEmpty()
        }
        val began = now()
        spinUntil(0.4) { false }
        assertTrue(!focus.isCompleted, "focusAt returned while the camera was still focusing")

        driver.back.adjustFocus(false)
        settleUntil("focusAt returns") { focus.isCompleted }
        assertEquals(CameraKResult.Success(Unit), focus.await())
        assertTrue(now() - began < 1.5, "focusAt waited for its timeout instead of the settled focus")
        assertEquals(0, driver.back.focusObserverCount, "the focus is still observed after it settled")
        engine.closeAndJoin()
    }

    @Test
    fun aFocusStillAdjustingAfterTwoSecondsTimesOut() = driver.runCase {
        val engine = started()
        driver.back.adjustFocus(true)
        val began = now()
        val result = engine.focusAt(NormalizedPoint(0.3f, 0.3f))
        assertEquals(CameraOperation.FOCUS_POINT, assertIs<CameraKError.Timeout>(failure(result)).operation)
        assertTrue(now() - began >= 1.9, "the timeout came after ${now() - began} s, not 2 s")
        engine.closeAndJoin()
    }

    @Test
    fun aCameraThatMetersOnlyExposureSucceedsOnceTheConfigurationIsCommitted() = driver.runCase {
        val engine = started()
        assertIs<CameraKResult.Success<*>>(engine.selectCamera(CameraSelection.ById(AppleEngineDriver.FRONT_ID)))
        driver.front.adjustFocus(true)

        assertEquals(CameraKResult.Success(Unit), engine.focusAt(NormalizedPoint(0.5f, 0.5f)))
        assertEquals(
            listOf("setExposurePointOfInterest", "setExposureMode", "setSubjectAreaChangeMonitoringEnabled"),
            driver.front.calls.filter { it.op in FOCUS_OPS }.map { it.op },
        )
        assertEquals(0, driver.front.focusObserverCount, "a camera without a focus point is waited on")
        engine.closeAndJoin()
    }

    @Test
    fun aNewerFocusAtSupersedesOneInFlight() = driver.runCase {
        val engine = started()
        driver.back.adjustFocus(true)
        val first = async { engine.focusAt(NormalizedPoint(0.2f, 0.2f)) }
        settleUntil("the first point reaches the camera") { driver.back.callsOf("setFocusPointOfInterest").size == 1 }
        val second = async { engine.focusAt(NormalizedPoint(0.8f, 0.8f)) }
        settleUntil("the first focusAt returns") { first.isCompleted }

        assertEquals(CameraOperation.FOCUS_POINT, assertIs<CameraKError.Busy>(failure(first.await())).operation)
        driver.back.adjustFocus(false)
        settleUntil("the second focusAt returns") { second.isCompleted }
        assertEquals(CameraKResult.Success(Unit), second.await())
        engine.closeAndJoin()
    }

    @Test
    fun aResetSupersedesAFocusInFlight() = driver.runCase {
        val engine = started()
        driver.back.adjustFocus(true)
        val focus = async { engine.focusAt(NormalizedPoint(0.2f, 0.2f)) }
        settleUntil("the point reaches the camera") { driver.back.callsOf("setFocusPointOfInterest").isNotEmpty() }

        assertEquals(CameraKResult.Success(Unit), engine.resetFocusAndExposure())
        settleUntil("focusAt returns") { focus.isCompleted }
        assertEquals(CameraOperation.FOCUS_POINT, assertIs<CameraKError.Busy>(failure(focus.await())).operation)
        engine.closeAndJoin()
    }

    @Test
    fun aCameraSwitchSupersedesAFocusInFlight() = driver.runCase {
        val engine = started()
        driver.back.adjustFocus(true)
        val focus = async { engine.focusAt(NormalizedPoint(0.2f, 0.2f)) }
        settleUntil("the point reaches the camera") { driver.back.callsOf("setFocusPointOfInterest").isNotEmpty() }

        assertIs<CameraKResult.Success<*>>(engine.selectCamera(CameraSelection.ById(AppleEngineDriver.FRONT_ID)))
        settleUntil("focusAt returns") { focus.isCompleted }
        assertEquals(CameraOperation.FOCUS_POINT, assertIs<CameraKError.Busy>(failure(focus.await())).operation)
        engine.closeAndJoin()
    }

    @Test
    fun closeDuringTheWaitAnswersClosedAtOnce() = driver.runCase {
        val engine = started()
        driver.back.adjustFocus(true)
        val focus = async { engine.focusAt(NormalizedPoint(0.2f, 0.2f)) }
        settleUntil("the point reaches the camera") { driver.back.callsOf("setFocusPointOfInterest").isNotEmpty() }

        engine.close()
        settleUntil("focusAt returns") { focus.isCompleted }
        assertSame(CameraKError.Closed, failure(focus.await()))
        engine.closeAndJoin()
    }

    @Test
    fun stopDuringTheWaitAnswersClosed() = driver.runCase {
        val engine = started()
        driver.back.adjustFocus(true)
        val focus = async { engine.focusAt(NormalizedPoint(0.2f, 0.2f)) }
        settleUntil("the point reaches the camera") { driver.back.callsOf("setFocusPointOfInterest").isNotEmpty() }

        engine.stop()
        settleUntil("focusAt returns") { focus.isCompleted }
        assertSame(CameraKError.Closed, failure(focus.await()))
        engine.closeAndJoin()
    }

    @Test
    fun aRefusedConfigurationLockIsHardwareWithTheErrorsCode() = driver.runCase {
        val engine = started()
        val refusal = NSErrorException(NSError.errorWithDomain("AVFoundationErrorDomain", -11800, null))
        driver.back.lockRefusal = refusal
        driver.back.lockSucceeds = false

        val hardware = assertIs<CameraKError.Hardware>(failure(engine.focusAt(NormalizedPoint(0.5f, 0.5f))))
        assertEquals(-11800, hardware.platformCode)
        assertSame(refusal, hardware.cause)
        assertEquals(emptyList(), driver.back.callsOf("setFocusPointOfInterest"), "the point was set without the lock")
        engine.closeAndJoin()
    }

    @Test
    fun aSubjectAreaChangeReturnsToContinuousMeteringAtTheCentre() = driver.runCase {
        val engine = started()
        assertEquals(CameraKResult.Success(Unit), engine.focusAt(NormalizedPoint(0.2f, 0.7f)))
        val before = driver.back.calls.size

        driver.back.subjectAreaChanged()
        settleUntil("the camera returns to continuous metering") {
            driver.back.calls.drop(before).any { it.op == "setSubjectAreaChangeMonitoringEnabled" }
        }
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
        engine.closeAndJoin()
    }

    @Test
    fun theFocusPointIsNotReappliedAfterACameraSwitch() = driver.runCase {
        val engine = started()
        assertEquals(CameraKResult.Success(Unit), engine.focusAt(NormalizedPoint(0.2f, 0.7f)))
        assertIs<CameraKResult.Success<*>>(engine.selectCamera(CameraSelection.ById(AppleEngineDriver.FRONT_ID)))
        assertIs<CameraKResult.Success<*>>(engine.selectCamera(CameraSelection.ById(AppleEngineDriver.BACK_ID)))
        driver.settle()

        assertEquals(1, driver.back.callsOf("setFocusPointOfInterest").size, "the point was set again")
        assertEquals(emptyList(), driver.front.callsOf("setExposurePointOfInterest"), "the point moved to the front")
        engine.closeAndJoin()
    }

    @Test
    fun afterCloseEachCallIsClosedWithoutReachingTheCamera() = driver.runCase {
        val engine = started()
        engine.close()
        driver.settle()
        val before = driver.back.calls.size

        assertSame(CameraKError.Closed, failure(engine.focusAt(NormalizedPoint(0.5f, 0.5f))))
        assertSame(CameraKError.Closed, failure(engine.setExposureCompensation(2)))
        assertSame(CameraKError.Closed, failure(engine.resetFocusAndExposure()))
        driver.settle()
        assertEquals(before, driver.back.calls.size, "a call after close reached the camera")
    }

    @Test
    fun aCameraThatMetersAtNoPointAndHasNoBiasRangeIsUnsupported() {
        val plain = AppleEngineDriver()
        plain.runCase {
            val engine = plain.createEngine()
            plain.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            val capabilities = engine.capabilities.value
            assertTrue(!capabilities.supportsFocusPoint(AppleEngineDriver.BACK_ID), "a focus point is reported")
            assertEquals(null, capabilities.exposureCompensationRange(AppleEngineDriver.BACK_ID))

            val focus = assertIs<CameraKError.Unsupported>(failure(engine.focusAt(NormalizedPoint(0.5f, 0.5f))))
            assertEquals(CameraFeature.FOCUS_POINT, focus.feature)
            val exposure = assertIs<CameraKError.Unsupported>(failure(engine.setExposureCompensation(1)))
            assertEquals(CameraFeature.EXPOSURE_COMPENSATION, exposure.feature)
            val reset = assertIs<CameraKError.Unsupported>(failure(engine.resetFocusAndExposure()))
            assertEquals(CameraFeature.FOCUS_POINT, reset.feature)
            engine.closeAndJoin()
        }
    }

    private suspend fun started(): CameraEngine {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        driver.settle()
        return engine
    }

    private suspend fun settleUntil(what: String, condition: () -> Boolean) = driver.settleUntil(what, condition)
}

@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine.contract

import com.kashif.cameraK.controller.backCamera
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The iOS engine passes the contract kit over a fake session whose two cameras each have a flash, a
 * torch and a zoom range, so every hardware step runs, including the ones after a camera switch, which
 * check that the switch re-applies the desired hardware. The runtime-error, interruption and did-start-running
 * observers are its platform callbacks; the throwing one is the did-start-running observer, whose
 * read of the session's interruption the fake makes throw once. Flash is read back from the photo
 * output, so the driver is a [ReadBackFlashDriver], and exposure from the device's bias in its
 * completion, so it is a [ReadBackExposureDriver]. From Z-focus-ios both cameras meter at a point and
 * take exposure compensation, so the focus and exposure cases run their supported branches. Its
 * unsupported configuration is an RGBA_8888 analysis format: iOS frames are 420f only (I4').
 */
internal class AppleCameraEngineContractTest :
    CameraEngineContractTest(
        driver = AppleEngineDriver(adjustableBack = true, adjustableFront = true, metering = true),
        requires = setOf(
            ThrowingCallbackDriver::class,
            PreOpenInterruptionDriver::class,
            ReadBackFlashDriver::class,
            ReadBackExposureDriver::class,
            CaptureDriver::class,
            RecordingDriver::class,
            UnsupportedConfigurationDriver::class,
        ),
    ) {
    // The kit takes the unsupported branch for a camera the capabilities do not list.
    @Test
    fun theContractCamerasReportAFocusPointAndExposureCompensation() {
        val driver = AppleEngineDriver(adjustableBack = true, adjustableFront = true, metering = true)
        driver.runCase {
            val engine = driver.createEngine()
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            val front = CameraSelection.ById(AppleEngineDriver.FRONT_ID)
            assertIs<CameraKResult.Success<*>>(engine.selectCamera(front))
            driver.settle()

            val capabilities = engine.capabilities.value
            for (id in listOf(AppleEngineDriver.BACK_ID, AppleEngineDriver.FRONT_ID)) {
                assertTrue(capabilities.supportsFocusPoint(id), "camera '$id' reports FOCUS_POINT")
            }
            assertEquals(-6..6, capabilities.exposureCompensationRange(AppleEngineDriver.BACK_ID))
            assertEquals(1f / 3, capabilities.exposureCompensationStep(AppleEngineDriver.BACK_ID))
            assertEquals(-4..4, capabilities.exposureCompensationRange(AppleEngineDriver.FRONT_ID))
            assertEquals(1f / 3, capabilities.exposureCompensationStep(AppleEngineDriver.FRONT_ID))
            engine.closeAndJoin()
        }
    }
}

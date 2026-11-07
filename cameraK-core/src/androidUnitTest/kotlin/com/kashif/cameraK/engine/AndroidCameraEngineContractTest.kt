@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.BACK_WITH_FLASH
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.FRONT_WITH_FLASH
import com.kashif.cameraK.engine.contract.CameraEngineContractTest
import com.kashif.cameraK.engine.contract.CaptureDriver
import com.kashif.cameraK.engine.contract.PreOpenInterruptionDriver
import com.kashif.cameraK.engine.contract.ReadBackExposureDriver
import com.kashif.cameraK.engine.contract.ReadBackFlashDriver
import com.kashif.cameraK.engine.contract.RecordingDriver
import com.kashif.cameraK.engine.contract.ThrowingCallbackDriver
import com.kashif.cameraK.engine.contract.UnsupportedConfigurationDriver
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The engine contract over CameraX's camera state, with torch and zoom read back from CameraX's
 * observers, flash read back from the bound ImageCapture and the exposure index from ExposureState, on
 * two cameras with a flash unit and a zoom range. From A3' it records, captures and refuses HEIC at
 * start, so the kit skips nothing; from Z-focus-android its cameras meter at a point and take exposure
 * compensation, so the focus and exposure cases run their supported branches.
 */
@RunWith(RobolectricTestRunner::class)
internal class AndroidCameraEngineContractTest :
    CameraEngineContractTest(
        AndroidEngineDriver(),
        requires = setOf(
            ThrowingCallbackDriver::class,
            PreOpenInterruptionDriver::class,
            ReadBackFlashDriver::class,
            ReadBackExposureDriver::class,
            RecordingDriver::class,
            CaptureDriver::class,
            UnsupportedConfigurationDriver::class,
        ),
    ) {
    // The kit takes the unsupported branch for a camera the capabilities do not list: each contract camera
    // reports what the driver's fake meters with, once the engine has bound it.
    @Test
    fun theContractCamerasReportAFocusPointAndExposureCompensation() = runTest {
        val platform = FakeAndroidPlatform(
            FakeAndroidPlatform.CONTRACT_CAMERAS,
            meteringOf = FakeAndroidPlatform.CONTRACT_METERING,
        )
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        val front = CameraSelection.ByLens(CameraLens.FRONT, CameraDeviceType.DEFAULT)
        assertEquals(FRONT_WITH_FLASH.id, (engine.selectCamera(front) as CameraKResult.Success).value.id)
        platform.settle(testScheduler)

        val capabilities = engine.capabilities.value
        for (lens in listOf(BACK_WITH_FLASH, FRONT_WITH_FLASH)) {
            assertTrue(capabilities.supportsFocusPoint(lens.id), "camera '${lens.id}' reports FOCUS_POINT")
        }
        assertEquals(-12..12, capabilities.exposureCompensationRange(BACK_WITH_FLASH.id))
        assertEquals(1f / 6, capabilities.exposureCompensationStep(BACK_WITH_FLASH.id))
        assertEquals(-6..6, capabilities.exposureCompensationRange(FRONT_WITH_FLASH.id))
        assertEquals(1f / 3, capabilities.exposureCompensationStep(FRONT_WITH_FLASH.id))
        engine.closeAndJoin()
    }
}

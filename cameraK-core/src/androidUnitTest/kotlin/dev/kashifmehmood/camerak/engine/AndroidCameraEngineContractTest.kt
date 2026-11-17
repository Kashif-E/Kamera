@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.engine

import dev.kashifmehmood.camerak.engine.FakeAndroidPlatform.Companion.BACK_WITH_FLASH
import dev.kashifmehmood.camerak.engine.FakeAndroidPlatform.Companion.FRONT_WITH_FLASH
import dev.kashifmehmood.camerak.engine.contract.CameraEngineContractTest
import dev.kashifmehmood.camerak.engine.contract.CaptureDriver
import dev.kashifmehmood.camerak.engine.contract.PreOpenInterruptionDriver
import dev.kashifmehmood.camerak.engine.contract.ReadBackExposureDriver
import dev.kashifmehmood.camerak.engine.contract.ReadBackFlashDriver
import dev.kashifmehmood.camerak.engine.contract.RecordingDriver
import dev.kashifmehmood.camerak.engine.contract.ThrowingCallbackDriver
import dev.kashifmehmood.camerak.engine.contract.UnsupportedConfigurationDriver
import dev.kashifmehmood.camerak.enums.CameraDeviceType
import dev.kashifmehmood.camerak.enums.CameraLens
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
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

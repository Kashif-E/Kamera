@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.controller

import androidx.camera.core.ImageCapture
import dev.kashifmehmood.camerak.engine.CameraSelection
import dev.kashifmehmood.camerak.engine.DesiredHardware
import dev.kashifmehmood.camerak.engine.FakeAndroidPlatform
import dev.kashifmehmood.camerak.enums.CameraDeviceType
import dev.kashifmehmood.camerak.enums.CameraLens
import dev.kashifmehmood.camerak.enums.FlashMode
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The flash mode is a setting of the bound ImageCapture, kept across binds. */
@RunWith(RobolectricTestRunner::class)
class FlashModeTest {
    private val platform = FakeAndroidPlatform(FakeAndroidPlatform.CONTRACT_CAMERAS)

    @Test
    fun flashModeAskedForAtStart_isTheBoundCapturesModeAndSurvivesALensSwitch() = runTest {
        val engine = platform.engine(testScheduler)
        platform.settle(testScheduler)
        val desired = FakeAndroidPlatform.desired.let {
            DesiredHardware(it.selection, FlashMode.AUTO, it.torchMode, it.zoomRatio, it.targetOrientation)
        }

        assertEquals(CameraKResult.Success(Unit), engine.start(desired), "start")
        platform.settle(testScheduler)
        assertEquals(ImageCapture.FLASH_MODE_AUTO, boundCapture().flashMode)

        val switch = async { engine.selectCamera(CameraSelection.ByLens(CameraLens.FRONT, CameraDeviceType.DEFAULT)) }
        platform.settle(testScheduler)
        assertIs<CameraKResult.Success<*>>(switch.await(), "the lens switch")
        assertEquals(ImageCapture.FLASH_MODE_AUTO, boundCapture().flashMode, "the lens switch lost the flash mode")
        assertEquals(FlashMode.AUTO, engine.hardware.value.flashMode)
        engine.closeAndJoin()
    }

    private fun boundCapture(): ImageCapture = platform.backend.binding.bound.filterIsInstance<ImageCapture>().single()
}

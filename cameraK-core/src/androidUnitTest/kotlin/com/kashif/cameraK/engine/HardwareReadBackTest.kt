@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import androidx.camera.core.ImageCapture
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.controller.AndroidCameraEngine
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.BACK_WITH_FLASH
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.FLASH_AND_ZOOM
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.FRONT
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.desired
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.ZoomState
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `hardware` and `zoom` follow CameraX's own reports, never the request (design §2.3 rule 5, §6.2):
 * torch from `cameraInfo.torchState`, zoom from `cameraInfo.zoomState`, flash from a read-back of
 * the bound ImageCapture after each set and each bind. Zoom is clamped to the range CameraX reports,
 * which can start below 1x; a fixed-zoom camera takes only its one ratio. Torch AUTO does not exist
 * on Android.
 */
@RunWith(RobolectricTestRunner::class)
class HardwareReadBackTest {
    private val platform = FakeAndroidPlatform(FLASH_AND_ZOOM, holdReports = true)

    @Test
    fun torchFollowsCameraInfoNotTheRequest() = runTest {
        val engine = open()

        assertEquals(CameraKResult.Success(TorchMode.ON), engine.setTorchMode(TorchMode.ON))
        assertEquals(listOf(true), platform.camera!!.torchRequests, "the torch request did not reach CameraX")
        assertEquals(
            TorchMode.OFF,
            engine.hardware.value.torchMode,
            "hardware took the request before CameraX reported it",
        )

        platform.camera!!.reportTorch(true)
        platform.settle(testScheduler)
        assertEquals(TorchMode.ON, engine.hardware.value.torchMode, "hardware did not follow CameraX's torch report")

        // CameraX turns the torch off on its own, for example when the device overheats.
        platform.camera!!.reportTorch(false)
        platform.settle(testScheduler)
        assertEquals(TorchMode.OFF, engine.hardware.value.torchMode, "hardware missed CameraX turning the torch off")
        engine.closeAndJoin()
    }

    @Test
    fun zoomFollowsCameraInfo() = runTest {
        val engine = open()
        assertEquals(ZoomState(1f, 1f, 8f), engine.zoom.value, "the bound camera's reported zoom and range")

        assertEquals(CameraKResult.Success(ZoomState(3f, 1f, 8f)), engine.setZoom(3f))
        assertEquals(listOf(3f), platform.camera!!.zoomRequests, "the zoom request did not reach CameraX")
        assertEquals(ZoomState(1f, 1f, 8f), engine.zoom.value, "zoom took the request before CameraX reported it")

        platform.camera!!.reportZoom(2.5f)
        platform.settle(testScheduler)
        assertEquals(ZoomState(2.5f, 1f, 8f), engine.zoom.value, "zoom did not follow CameraX's report")
        engine.closeAndJoin()
    }

    @Test
    fun minZoomBelowOneIsReachable() = runTest {
        val ultraWide = LensInfo("0", CameraDeviceType.WIDE_ANGLE, CameraLens.BACK, 0.6f, 8f, true, true)
        val platform = FakeAndroidPlatform(listOf(ultraWide, FRONT), holdReports = true)
        val engine = platform.engine(testScheduler)
        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        platform.settle(testScheduler)

        assertEquals(CameraKResult.Success(ZoomState(0.6f, 0.6f, 8f)), engine.setZoom(0.6f))
        assertEquals(listOf(0.6f), platform.camera!!.zoomRequests, "0.6x was clamped to 1x")
        engine.closeAndJoin()
    }

    @Test
    fun torchAutoIsUnsupported() = runTest {
        val engine = open()

        val result = assertIs<CameraKResult.Failure>(engine.setTorchMode(TorchMode.AUTO), "torch AUTO")
        assertEquals(CameraFeature.TORCH, assertIs<CameraKError.Unsupported>(result.error).feature)
        assertTrue(platform.camera!!.torchRequests.isEmpty(), "torch AUTO was turned into a torch request")
        assertEquals(
            setOf(TorchMode.OFF, TorchMode.ON),
            engine.capabilities.value.supportedTorchModes(BACK_WITH_FLASH.id),
        )
        engine.closeAndJoin()
    }

    @Test
    fun configuredFlashAndTorchReachTheBoundCamera() = runTest {
        val platform = FakeAndroidPlatform(FLASH_AND_ZOOM)
        val engine = platform.engine(testScheduler)
        val configured = DesiredHardware(
            selection = desired.selection,
            flashMode = FlashMode.ON,
            torchMode = TorchMode.ON,
            zoomRatio = 1f,
            targetOrientation = null,
        )

        assertEquals(CameraKResult.Success(Unit), engine.start(configured))
        platform.settle(testScheduler)

        assertEquals(ImageCapture.FLASH_MODE_ON, boundCapture(platform).flashMode, "the configured flash")
        assertEquals(
            listOf(true),
            platform.camera!!.torchRequests,
            "the configured torch never reached the open camera",
        )
        assertEquals(FlashMode.ON, engine.hardware.value.flashMode)
        assertEquals(TorchMode.ON, engine.hardware.value.torchMode)
        engine.closeAndJoin()
    }

    @Test
    fun flashIsReadBackFromTheImageCaptureAfterEachSet() = runTest {
        // CameraX reports AUTO whatever the ImageCapture was given: hardware shows the read-back.
        platform.backend.flashReadBack = { ImageCapture.FLASH_MODE_AUTO }
        val engine = open()
        assertEquals(FlashMode.AUTO, engine.hardware.value.flashMode, "the flash read back at the bind")

        assertEquals(CameraKResult.Success(FlashMode.AUTO), engine.setFlashMode(FlashMode.ON))
        assertEquals(
            ImageCapture.FLASH_MODE_ON,
            boundCapture(platform).flashMode,
            "the set did not reach the ImageCapture",
        )
        assertEquals(FlashMode.AUTO, engine.hardware.value.flashMode, "hardware is the request, not the read-back")
        engine.closeAndJoin()
    }

    @Test
    fun outOfRangeZoomIsClampedToTheReportedRange() = runTest {
        val engine = open()

        assertEquals(CameraKResult.Success(ZoomState(8f, 1f, 8f)), engine.setZoom(20f))
        assertEquals(CameraKResult.Success(ZoomState(1f, 1f, 8f)), engine.setZoom(0.2f))
        assertEquals(listOf(8f, 1f), platform.camera!!.zoomRequests, "the clamped ratios CameraX was given")
        engine.closeAndJoin()
    }

    @Test
    fun fixedZoomCameraRefusesAnyOtherRatio() = runTest {
        val engine = platform.engine(testScheduler)
        assertEquals(CameraKResult.Success(Unit), engine.start(on(FRONT)))
        platform.settle(testScheduler)

        assertEquals(CameraKResult.Success(ZoomState(1f, 1f, 1f)), engine.setZoom(1f))
        val refused = assertIs<CameraKResult.Failure>(engine.setZoom(2f), "2x on a fixed-zoom camera")
        assertEquals(CameraFeature.ZOOM, assertIs<CameraKError.Unsupported>(refused.error).feature)
        assertTrue(2f !in platform.camera!!.zoomRequests, "a ratio the camera cannot take reached CameraX")
        engine.closeAndJoin()
    }

    private suspend fun TestScope.open(): AndroidCameraEngine {
        val engine = platform.engine(testScheduler)
        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        platform.settle(testScheduler)
        assertEquals(EngineState.Open, engine.state.value)
        return engine
    }

    private fun on(lens: LensInfo) = DesiredHardware(
        selection = CameraSelection.ById(lens.id),
        flashMode = FlashMode.OFF,
        torchMode = TorchMode.OFF,
        zoomRatio = 1f,
        targetOrientation = null,
    )

    private fun boundCapture(platform: FakeAndroidPlatform): ImageCapture =
        platform.backend.binding.bound.filterIsInstance<ImageCapture>().single()
}

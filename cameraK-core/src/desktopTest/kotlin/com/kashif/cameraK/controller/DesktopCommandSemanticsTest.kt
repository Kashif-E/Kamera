@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.desktopEngine
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.ZoomState
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.bytedeco.javacv.FrameGrabber
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The desktop command semantics (§2.4 desktop): a webcam has no flash, no torch and a fixed zoom
 * of 1, so OFF and 1.0 succeed and anything else is Unsupported, and the capabilities list only
 * OFF. The target orientation is configuration: accepted in every state but closed, a no-op on
 * desktop (W4 decision G2). Flash, torch and zoom are hardware commands: NotReady in every state
 * but Open, as on the fake (D1' carry-over 3).
 */
class DesktopCommandSemanticsTest {
    private val desired = DesiredHardware(
        selection = CameraSelection.ByLens(CameraLens.BACK, CameraDeviceType.DEFAULT),
        flashMode = FlashMode.OFF,
        torchMode = TorchMode.OFF,
        zoomRatio = 1f,
        targetOrientation = null,
    )

    @Test
    fun zoomOneSucceedsOtherRatiosUnsupported() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val sut = rig.engine()
        try {
            open(rig, sut)

            assertEquals(CameraKResult.Success(ZoomState(1f, 1f, 1f)), sut.setZoom(1f))
            for (ratio in listOf(0.5f, 1.01f, 2f)) {
                assertUnsupported(CameraFeature.ZOOM, sut.setZoom(ratio), "setZoom($ratio)")
            }
            assertEquals(ZoomState(1f, 1f, 1f), sut.zoom.value)
        } finally {
            sut.close()
        }
    }

    @Test
    fun flashAndTorchOffSucceedOthersUnsupported() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val sut = rig.engine()
        try {
            open(rig, sut)

            assertEquals(CameraKResult.Success(FlashMode.OFF), sut.setFlashMode(FlashMode.OFF))
            assertUnsupported(CameraFeature.FLASH_ON, sut.setFlashMode(FlashMode.ON))
            assertUnsupported(CameraFeature.FLASH_AUTO, sut.setFlashMode(FlashMode.AUTO))
            assertEquals(CameraKResult.Success(TorchMode.OFF), sut.setTorchMode(TorchMode.OFF))
            assertUnsupported(CameraFeature.TORCH, sut.setTorchMode(TorchMode.ON))
            assertUnsupported(CameraFeature.TORCH, sut.setTorchMode(TorchMode.AUTO))
            assertEquals(FlashMode.OFF, sut.hardware.value.flashMode)
            assertEquals(TorchMode.OFF, sut.hardware.value.torchMode)
        } finally {
            sut.close()
        }
    }

    @Test
    fun targetOrientationIsNoOpSuccess() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        rig.camera.holdOpen = true
        val sut = rig.engine()
        try {
            val ok = CameraKResult.Success(Unit)
            assertEquals(ok, sut.setTargetOrientation(DeviceOrientation.LANDSCAPE_LEFT), "before the first start")

            val start = async { sut.start(desired) }
            rig.settle()
            assertEquals(EngineState.Opening, sut.state.value)
            assertEquals(ok, sut.setTargetOrientation(DeviceOrientation.PORTRAIT), "while opening")
            rig.camera.deliverFrame()
            rig.settle()
            assertEquals(ok, start.await())

            val before = sut.hardware.value
            assertEquals(ok, sut.setTargetOrientation(DeviceOrientation.LANDSCAPE_LEFT), "while open")
            assertEquals(ok, sut.setTargetOrientation(null), "following the device again")
            assertEquals(before, sut.hardware.value, "desktop applies no orientation to its outputs")

            sut.stop()
            assertEquals(ok, sut.setTargetOrientation(DeviceOrientation.LANDSCAPE_LEFT), "after stop")
            sut.close()
            assertEquals(
                CameraKResult.Failure(CameraKError.Closed),
                sut.setTargetOrientation(DeviceOrientation.LANDSCAPE_LEFT),
                "after close",
            )
        } finally {
            sut.close()
        }
    }

    @Test
    fun capabilitiesListOnlyOffForFlashAndTorch() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val devices = object : DesktopDevices {
            override fun list(): List<LensInfo> = listOf(webcam("usb-a"), webcam("usb-b"))

            override fun open(id: String, size: Pair<Int, Int>?): FrameGrabber = rig.camera
        }
        val sut = desktopEngine(CameraConfiguration(), CameraKPlatformContext(), rig.environment(), devices = devices)
        try {
            runCurrent()

            val capabilities = sut.capabilities.value
            assertEquals(listOf("usb-a", "usb-b"), capabilities.allLenses.map { it.id })
            for (id in listOf("usb-a", "usb-b")) {
                assertEquals(setOf(FlashMode.OFF), capabilities.supportedFlashModes(id), "flash of $id")
                assertEquals(setOf(TorchMode.OFF), capabilities.supportedTorchModes(id), "torch of $id")
            }
            assertEquals(true, capabilities.supportsPauseResume)
        } finally {
            sut.close()
        }
    }

    @Test
    fun hardwareCommandsAreNotReadyUntilOpen() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val sut = rig.engine()
        try {
            assertNotReady(sut, "before the first start")

            rig.camera.holdOpen = true
            val start = async { sut.start(desired) }
            rig.settle()
            assertEquals(EngineState.Opening, sut.state.value)
            assertNotReady(sut, "while opening")
            rig.camera.deliverFrame()
            rig.settle()
            assertEquals(CameraKResult.Success(Unit), start.await())

            // The camera stops delivering: Interrupted while the engine reopens it.
            rig.camera.failGrab(IOException("the camera stopped delivering frames"))
            rig.settle()
            assertIs<EngineState.Interrupted>(sut.state.value)
            assertNotReady(sut, "while interrupted")
            rig.camera.deliverFrame()
            rig.settle()
            assertEquals(EngineState.Open, sut.state.value)

            rig.camera.failGrab(NoClassDefFoundError("Could not initialize class org.bytedeco.ffmpeg.global.avutil"))
            rig.settle()
            assertIs<EngineState.Failed>(sut.state.value)
            assertNotReady(sut, "after a failure")

            sut.stop()
            assertNotReady(sut, "after stop")
        } finally {
            sut.close()
        }
    }

    @Test
    fun hardwareCommandsAfterCloseAreClosed() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val sut = rig.engine()
        open(rig, sut)
        sut.close()

        val closed = CameraKResult.Failure(CameraKError.Closed)
        assertEquals(closed, sut.setFlashMode(FlashMode.ON))
        assertEquals(closed, sut.setTorchMode(TorchMode.ON))
        assertEquals(closed, sut.setZoom(2f))
    }

    private suspend fun assertNotReady(sut: DesktopCameraEngine, `when`: String) {
        val notReady = CameraKResult.Failure(CameraKError.NotReady)
        assertEquals(notReady, sut.setFlashMode(FlashMode.OFF), "setFlashMode(OFF) $`when`")
        assertEquals(notReady, sut.setFlashMode(FlashMode.ON), "setFlashMode(ON) $`when`")
        assertEquals(notReady, sut.setTorchMode(TorchMode.OFF), "setTorchMode(OFF) $`when`")
        assertEquals(notReady, sut.setZoom(1f), "setZoom(1) $`when`")
    }

    private suspend fun TestScope.open(rig: DesktopEngineRig, sut: DesktopCameraEngine) {
        val start = async { sut.start(desired) }
        rig.settle()
        assertEquals(CameraKResult.Success(Unit), start.await())
        assertEquals(EngineState.Open, sut.state.value)
    }

    private fun assertUnsupported(feature: CameraFeature, result: CameraKResult<*>, message: String = "$feature") {
        val error = assertIs<CameraKError.Unsupported>(assertIs<CameraKResult.Failure>(result, message).error, message)
        assertEquals(feature, error.feature, message)
    }

    private fun webcam(id: String) =
        LensInfo(id, CameraDeviceType.DEFAULT, CameraLens.BACK, 1f, 1f, hasFlash = false, isLogical = false)
}

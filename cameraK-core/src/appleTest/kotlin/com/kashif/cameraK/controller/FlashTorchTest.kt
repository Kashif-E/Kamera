package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import platform.AVFoundation.AVCaptureFlashModeOff
import platform.AVFoundation.AVCaptureFlashModeOn
import platform.AVFoundation.AVCaptureTorchModeOn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The flash and torch modes a CameraController is configured with have to reach AVFoundation: a
 * configured FlashMode.OFF must not fire the flash, and a configured torch must light once the
 * session runs and again on whichever lens the user switches to.
 */
class FlashTorchTest {
    private val back = FakeDevice(isFront = false)
    private val front = FakeDevice(isFront = true)

    private fun custom() = CustomCameraController(
        qualityPrioritization = QualityPrioritization.NONE,
        sessionFactory = { RecordingSession() },
        cameraDevices = { lens, _ ->
            when (lens) {
                CameraLens.FRONT -> front
                CameraLens.BACK, null -> back
            }
        },
    )

    private fun cameraController(flash: FlashMode, torch: TorchMode, custom: CustomCameraController) = CameraController(
        flashMode = flash,
        torchMode = torch,
        cameraLens = CameraLens.BACK,
        imageFormat = ImageFormat.JPEG,
        qualityPriority = QualityPrioritization.NONE,
        directory = Directory.DOCUMENTS,
        cameraDeviceType = CameraDeviceType.DEFAULT,
        aspectRatio = AspectRatio.RATIO_4_3,
        targetResolution = null,
        mirrorFrontCamera = false,
        customController = custom,
    )

    @Test
    fun theConfiguredFlashModeIsWhatCapturesUse() {
        val custom = custom()
        val controller = cameraController(FlashMode.OFF, TorchMode.OFF, custom)

        assertEquals(FlashMode.OFF, controller.getFlashMode(), "a FlashMode.OFF config reports another mode")
        assertEquals(AVCaptureFlashModeOff, custom.flashMode, "a FlashMode.OFF config would still fire the flash")
    }

    @Test
    fun aFlashModeSetBeforeTheSessionIsReadyIsKept() {
        val custom = custom()
        val controller = cameraController(FlashMode.OFF, TorchMode.OFF, custom)

        controller.setFlashMode(FlashMode.ON)

        assertEquals(AVCaptureFlashModeOn, custom.flashMode, "the requested flash mode was dropped before setup")
        assertEquals(FlashMode.ON, controller.getFlashMode())
    }

    @Test
    fun theConfiguredTorchLightsOnceTheSessionRunsAndFollowsALensSwitch() {
        val custom = custom()
        val controller = cameraController(FlashMode.OFF, TorchMode.ON, custom)

        controller.view // loads the view, which sets the session up and starts it
        awaitTrue("the configured torch to reach the back camera") {
            back.callsOf("setTorchMode").any { it.value == AVCaptureTorchModeOn }
        }
        val applied = back.callsOf("setTorchMode").last()
        assertTrue(applied.locked, "the torch was set without the configuration lock: $applied")

        controller.toggleCameraLens()
        awaitTrue("the configured torch to reach the front camera after the lens switch") {
            front.callsOf("setTorchMode").any { it.value == AVCaptureTorchModeOn }
        }
        controller.cleanup()
        drainSessionThenMain(custom)
    }
}

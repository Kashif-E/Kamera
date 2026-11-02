package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A lens or device-type switch swaps the camera input inside one configuration transaction on the
 * running session: it never stops the session, it removes the camera input rather than whatever
 * input comes first, a refused input leaves the previous camera in place, and it does not cut an
 * active recording.
 */
class LensSwitchTest {
    private val back = FakeDevice(isFront = false, maxVideoZoomFactor = 10.0)
    private val front = FakeDevice(isFront = true, maxVideoZoomFactor = 4.0)
    private val telephoto = FakeDevice(isFront = false, maxVideoZoomFactor = 15.0)
    private val session = RecordingSession()
    private val errors = mutableListOf<CustomCameraController.CameraException>()

    private val controller = CustomCameraController(
        qualityPrioritization = QualityPrioritization.NONE,
        sessionFactory = { session },
        cameraDevices = { lens, type ->
            when {
                type == CameraDeviceType.TELEPHOTO -> telephoto
                lens == CameraLens.FRONT -> front
                else -> back
            }
        },
    ).also { it.onError = { error -> errors += error } }

    private fun startRunning() {
        controller.setupSession()
        controller.startSession()
        drainSessionThenMain(controller)
        assertTrue(session.isRunning, "the session did not start")
        assertEquals(listOf(back.input), session.inputs, "setup did not attach the back camera")
    }

    @AfterTest
    fun tearDown() {
        controller.cleanupSession()
        drainSessionThenMain(controller)
    }

    @Test
    fun aSwitchSwapsTheCameraInputWithoutStoppingTheSession() {
        startRunning()
        val before = session.calls.size

        controller.switchCamera()
        drainSessionThenMain(controller)

        val calls = session.calls.drop(before)
        assertFalse(calls.any { it.op == "stopRunning" }, "the switch stopped the running session: $calls")
        assertEquals(listOf(front.input), session.inputs)
        assertTrue(session.isRunning)
        val swap = calls.filter { it.op == "removeInput" || it.op == "addInput" }
        assertTrue(
            swap.isNotEmpty() &&
                swap.all {
                    it.openConfigurations == 1
                },
            "inputs swapped outside one transaction: $swap",
        )
        assertEquals(0, session.openConfigurations, "the switch left a configuration open")
    }

    @Test
    fun theMicrophoneSurvivesLensSwitches() {
        startRunning()
        val microphone = captureInputWithoutCamera()
        controller.addInputUnlessPresent(
            what = "the microphone",
            isPresent = { it === microphone },
            create = { Result.success(microphone) },
        )

        controller.switchCamera()
        controller.switchCamera()
        drainSessionThenMain(controller)

        val inputs = session.inputs
        assertTrue(microphone in inputs, "a lens switch removed the microphone: $inputs")
        assertEquals(
            setOf(back.input, microphone),
            inputs.toSet(),
            "the session should hold the back camera and the microphone",
        )
        assertEquals(2, inputs.size, "the session holds a stray input: $inputs")
    }

    @Test
    fun aRefusedLensInputLeavesThePreviousCameraInPlace() {
        startRunning()
        session.refuse(front.input!!)

        controller.switchCamera()
        drainSessionThenMain(controller)

        assertEquals(listOf(back.input), session.inputs, "a refused switch left the session without its camera")
        assertEquals(10f, controller.getMaxZoom(), "the controller believes it moved to the front camera")
        assertTrue(session.isRunning)
        assertEquals(1, errors.size, "a refused switch was not reported: $errors")

        // The controller still knows it is on the back camera, so the next switch goes to the front.
        session.allow(front.input!!)
        controller.switchCamera()
        drainSessionThenMain(controller)
        assertEquals(listOf(front.input), session.inputs)
    }

    @Test
    fun aRefusedDeviceTypeLeavesThePreviousCameraInPlace() {
        startRunning()
        session.refuse(telephoto.input!!)

        controller.switchToDeviceType(CameraDeviceType.TELEPHOTO)
        drainSessionThenMain(controller)

        assertEquals(listOf(back.input), session.inputs, "a refused device type left the session without its camera")
        assertEquals(10f, controller.getMaxZoom(), "the controller believes it moved to the telephoto camera")
        assertEquals(1, errors.size, "a refused device-type switch was not reported: $errors")
    }

    @Test
    fun aSwitchDuringARecordingIsRefused() {
        startRunning()
        controller.recordingInProgress = { true }
        val before = session.calls.size

        controller.switchCamera()
        drainSessionThenMain(controller)

        val calls = session.calls.drop(before).filter { it.op != "inputs" }
        assertEquals(emptyList(), calls.map { it.toString() }, "a switch during a recording touched the session")
        assertEquals(listOf(back.input), session.inputs)
        assertEquals(1, errors.size, "the refused switch was not reported: $errors")
    }

    @Test
    fun cameraControllerKeepsItsLensAndDeviceTypeWhenASwitchIsRefusedDuringARecording() {
        val cameraController = CameraController(
            flashMode = FlashMode.OFF,
            torchMode = TorchMode.OFF,
            cameraLens = CameraLens.BACK,
            imageFormat = ImageFormat.JPEG,
            qualityPriority = QualityPrioritization.NONE,
            directory = Directory.DOCUMENTS,
            cameraDeviceType = CameraDeviceType.DEFAULT,
            aspectRatio = AspectRatio.RATIO_4_3,
            targetResolution = null,
            mirrorFrontCamera = false,
            customController = controller,
        )
        controller.recordingInProgress = { true }

        cameraController.toggleCameraLens()

        assertEquals(
            CameraLens.BACK,
            cameraController.getCameraLens(),
            "the lens was reported switched while recording",
        )

        cameraController.setPreferredCameraDeviceType(CameraDeviceType.TELEPHOTO)

        assertEquals(
            CameraDeviceType.DEFAULT,
            cameraController.getPreferredCameraDeviceType(),
            "the device type was reported changed while recording",
        )
    }
}

package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import kotlinx.coroutines.runBlocking
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureMovieFileOutput
import platform.darwin.NSObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A photo-only session carries no movie or metadata output. The metadata output is attached while a
 * scanner is set, and a recording's movie output and microphone leave the session when it ends.
 */
class OnDemandOutputsTest {
    private val back = FakeDevice()
    private val session = RecordingSession()
    private val custom = CustomCameraController(
        qualityPrioritization = QualityPrioritization.NONE,
        sessionFactory = { session },
        cameraDevices = { _, _ -> back },
    )
    private val controller = CameraController(
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
        customController = custom,
    )

    private fun setUpSession() {
        custom.setupSession()
        drainSessionThenMain(custom)
        assertEquals(listOf(back.input), session.inputs, "setup did not attach the camera")
    }

    private fun movieOutputs() = session.outputs.filterIsInstance<AVCaptureMovieFileOutput>()

    private fun metadataOutputs() = session.outputs.filterIsInstance<AVCaptureMetadataOutput>()

    @AfterTest
    fun tearDown() {
        controller.cleanup()
        drainSessionThenMain(custom)
    }

    @Test
    fun aPhotoOnlySessionHasNoMovieOrMetadataOutput() {
        setUpSession()

        assertEquals(emptyList(), movieOutputs(), "a photo-only session carries a movie file output")
        assertEquals(emptyList(), metadataOutputs(), "a session with no scanner carries a metadata output")
    }

    @Test
    fun theMetadataOutputIsAttachedOnlyWhileAScannerIsSet() {
        setUpSession()
        val scanner = object : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {}

        controller.setMetadataObjectsDelegate(scanner)
        drainSessionThenMain(custom)
        assertEquals(1, metadataOutputs().size, "setting a scanner did not attach the metadata output")

        controller.clearMetadataObjectsDelegate()
        drainSessionThenMain(custom)
        assertEquals(emptyList(), metadataOutputs(), "clearing the scanner left the metadata output attached")
    }

    @Test
    fun aRecordingsMovieOutputAndMicrophoneLeaveTheSessionWhenItEnds() {
        setUpSession()

        val output = assertNotNull(runBlocking { controller.attachMovieOutput() }, "no movie output for a recording")
        assertTrue(output in session.outputs, "the recording's movie output is not attached")
        val microphone = captureInputWithoutCamera()
        custom.addInputUnlessPresent(isPresent = { it === microphone }, create = { microphone })

        controller.releaseRecordingResources(output) { it === microphone }
        drainSessionThenMain(custom)

        assertFalse(output in session.outputs, "the movie output stayed attached after the recording ended")
        assertEquals(listOf(back.input), session.inputs, "the microphone stayed attached after the recording ended")
    }
}

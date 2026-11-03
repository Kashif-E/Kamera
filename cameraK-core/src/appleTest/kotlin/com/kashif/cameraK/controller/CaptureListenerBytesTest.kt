package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.utils.toNSData
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Image-capture listeners receive the captured photo's bytes: exactly those bytes, in an array no
 * later capture reuses. A pooled buffer is as large as the biggest earlier photo, so it hands
 * listeners trailing bytes from that photo, and it goes back to the pool while they still read it.
 */
class CaptureListenerBytesTest {
    private val session = RecordingSession()
    private val capturer = FakePhotoCapturer()
    private val custom = CustomCameraController(
        qualityPrioritization = QualityPrioritization.NONE,
        sessionFactory = { session },
        cameraDevices = { _, _ -> FakeDevice() },
        photoCapturer = capturer,
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

    @AfterTest
    fun tearDown() {
        controller.cleanup()
        drainSessionThenMain(custom)
    }

    @Test
    fun listenersReceiveExactlyTheCapturedBytes() {
        val received = mutableListOf<ByteArray>() // listeners run on main, as does this test
        controller.addImageCaptureListener { received += it }
        controller.startSession() // its own engine sets the session up and starts it, with no view
        awaitTrue("the session to start") { session.isRunning }

        val photo = ByteArray(100) { it.toByte() }

        custom.captureImage()
        awaitTrue("the capture request") { capturer.requests.size == 1 }
        capturer.requests.single().callbacks.photoProcessed(photo.toNSData(), null)
        awaitTrue("the listener to receive the photo") { received.isNotEmpty() }

        assertEquals(photo.size, received.single().size, "the listener got an array of the wrong size")
        assertContentEquals(photo, received.single(), "the listener got bytes other than the photo's")
    }
}

package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.utils.toNSData
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs

/**
 * A capture's outcome goes only to the takePictureToFile call that asked for it, and image-capture
 * listeners hear about every capture. One shared onPhotoCapture/onError slot that takePictureToFile
 * overwrites does neither: listeners never fire, and a session error raised while a capture is in
 * flight fails that capture.
 */
class CaptureRoutingTest {
    private val device = FakeDevice()
    private val rig = CaptureRig(device = device)

    @AfterTest
    fun tearDown() = rig.tearDown()

    @Test
    fun listenersHearAboutAPhotoTakenWithTakePictureToFile() {
        val received = mutableListOf<ByteArray>() // listeners run on main, as does this test
        rig.controller.addImageCaptureListener { received += it }
        rig.start()
        val photo = ByteArray(64) { (it * 3).toByte() }

        val (capture, request) = rig.capture()
        request.callbacks.photoProcessed(photo.toNSData(), null)
        request.callbacks.captureFinished(null)

        val result = assertIs<ImageCaptureResult.SuccessWithFile>(capture.awaitResult())
        assertContentEquals(photo, fileBytes(result.filePath), "the saved file is not the captured photo")
        awaitTrue("the image-capture listener to hear about the photo") { received.isNotEmpty() }
        assertContentEquals(photo, received.single(), "the listener got bytes other than the photo's")
    }

    @Test
    fun aSessionErrorRaisedDuringACaptureDoesNotFailIt() {
        rig.start()
        val photo = ByteArray(32) { it.toByte() }
        val (capture, request) = rig.capture()

        // A session-level failure while the capture is in flight: the camera refuses its lock.
        device.lockSucceeds = false
        rig.controller.setTorchMode(TorchMode.ON)
        drainSessionThenMain(rig.custom)

        request.callbacks.photoProcessed(photo.toNSData(), null)
        request.callbacks.captureFinished(null)

        val result = capture.awaitResult()
        assertIs<ImageCaptureResult.SuccessWithFile>(result, "an unrelated session error decided the capture: $result")
        assertContentEquals(photo, fileBytes(result.filePath), "the saved file is not the captured photo")
    }
}

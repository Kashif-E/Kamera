@file:OptIn(InternalCameraKApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.InternalCameraKApi
import com.kashif.cameraK.utils.toNSData
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs

/**
 * A capture's outcome goes only to the capture call that asked for it: a session error raised while
 * a capture is in flight does not fail that capture, as one shared onPhotoCapture/onError slot did.
 */
class CaptureRoutingTest {
    private val device = FakeDevice()
    private val rig = CaptureRig(device = device)

    @AfterTest
    fun tearDown() = rig.tearDown()

    @Test
    fun aSessionErrorRaisedDuringACaptureDoesNotFailIt() {
        rig.start()
        val photo = ByteArray(32) { it.toByte() }
        val (capture, request) = rig.capture()

        // A session-level failure while the capture is in flight: the camera refuses its lock.
        device.lockSucceeds = false
        rig.launch { setTorchMode(TorchMode.ON) }
        drainSessionThenMain(rig.custom)

        request.callbacks.photoProcessed(photo.toNSData(), null)
        request.callbacks.captureFinished(null)

        val result = capture.awaitResult()
        val image = assertIs<CameraKResult.Success<CapturedImage>>(
            result,
            "an unrelated session error decided the capture: $result",
        ).value
        assertContentEquals(photo, fileBytes(image.path), "the saved file is not the captured photo")
    }
}

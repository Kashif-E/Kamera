package com.kashif.cameraK.testing

import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.frames.FrameSpec
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKState
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Written as an app's test would be: no `@OptIn` in this file and no module-wide opt-in in this
 * source set. It stops compiling as soon as a scripting member of the fake names an engine-API type.
 */
class AppStyleFakeUsageTest {
    @Test
    fun fakeEngineScriptingNeedsNoOptIn() {
        val capabilities = FakeCapabilities.backAndFront(imageFormats = setOf(ImageFormat.JPEG))
        val fake =
            FakeCameraEngine(
                capabilities,
                initialFlash = FlashMode.AUTO,
                initialTorch = TorchMode.OFF,
                initialZoom = 2f,
            )

        assertEquals(emptyList(), fake.calls)
        assertEquals(0, fake.startCalls + fake.stopCalls + fake.closeCalls + fake.setZoomCalls)
        assertTrue(fake.autoOpen)
        assertTrue(fake.autoStartRecording)
        assertTrue(fake.autoFinalizeRecording)
        assertTrue(fake.supportsPause, "backAndFront supports pause and resume")
        assertEquals(FlashMode.AUTO, fake.appliedFlashMode)
        assertEquals(TorchMode.OFF, fake.appliedTorchMode)
        assertEquals(2f, fake.appliedZoomRatio)
        assertNull(fake.appliedCameraId, "nothing is bound before the first open")
        assertEquals(0, fake.releaseCount)

        fake.autoOpen = false
        fake.autoStartRecording = false
        fake.autoFinalizeRecording = false
        fake.supportsPause = false
        fake.configuration = CameraConfiguration(imageFormat = ImageFormat.JPEG)
        fake.captureResult = { CameraKResult.Success(FakeCaptures.image(path = "/fake/IMG_0002.jpg")) }

        // The platform side, scripted with nothing open: none of it changes the closed engine.
        fake.deliverOpen()
        fake.failOpen(CameraKError.CameraInUse())
        fake.startReturnsWithoutPublishing(CameraKError.Disconnected())
        fake.interrupt(InterruptionReason.APP_IN_BACKGROUND)
        fake.endInterruption()
        fake.fail(CameraKError.Disconnected())
        fake.reopenAfterFailure()
        fake.holdNextCapture()
        fake.releaseCapture()
        fake.finishRecording(
            CameraKResult.Success(FakeCaptures.video(duration = 2.seconds)),
            RecordingEndReason.MAX_DURATION,
        )
        fake.deliverRecordingStarted()
        fake.deliverRecordingFinalized()
        fake.throwFromNextCallback()
        fake.reportFlash(FlashMode.ON)
        fake.reportTorch(TorchMode.ON)
        fake.reportZoom(3f)
        fake.reportCamera(FakeCapabilities.FRONT_ID)

        val registration = fake.fakeFrames.register(FrameSpec()) { }
        assertTrue(registration.active.value, "a registration the session format accepts is active")
        assertNull(registration.rejection)
        registration.close()
        registration.close()

        assertFalse(fake.supportsPause)
        assertEquals(emptyList(), fake.calls, "scripting the platform is not an engine call")

        fake.close()
        assertEquals(listOf("close"), fake.calls)
        assertEquals(1, fake.closeCalls)
        assertEquals(1, fake.releaseCount)
    }

    @Test
    fun fakeCapturesBuildValuesWithoutOptIn() {
        val image = FakeCaptures.image(width = 640, height = 480, format = ImageFormat.PNG)
        val video = FakeCaptures.video(hasAudio = true)

        assertEquals("/fake/IMG_0001.jpg", image.path)
        assertEquals(640 to 480, image.width to image.height)
        assertEquals(ImageFormat.PNG, image.format)
        assertEquals("/fake/VID_0001.mp4", video.path)
        assertEquals(1.seconds, video.duration)
        assertTrue(video.hasAudio)
    }

    @Test
    fun fakeCameraKStateHolderNeedsNoOptIn() = runTest {
        val fake = FakeCameraEngine()
        fake.autoOpen = false
        val holder = fakeCameraKStateHolder(fake = fake)

        holder.start()
        runCurrent()
        assertEquals(CameraKState.Starting, holder.cameraState.value)

        fake.deliverOpen()
        runCurrent()
        assertIs<CameraKState.Ready>(holder.cameraState.value)

        assertEquals(CameraKResult.Success(FakeCaptures.image()), holder.capture())
        val uiState = holder.uiState.value
        assertEquals(FakeCapabilities.BACK_ID, uiState.cameraId)
        assertEquals(FlashMode.OFF, uiState.flashMode)
        assertEquals(0, uiState.pendingCaptures)
        assertFalse(uiState.isCapturing)

        holder.close()
        runCurrent()
        assertEquals(CameraKState.Closed, holder.cameraState.value)
        assertEquals(1, fake.closeCalls)
    }
}

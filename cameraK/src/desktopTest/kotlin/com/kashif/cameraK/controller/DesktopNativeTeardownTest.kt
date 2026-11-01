package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.video.VideoCaptureResult
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Native handles (the frame grabber, the recorder) are freed only after the coroutine using them
 * has left them, and exactly once, however teardown is reached.
 */
class DesktopNativeTeardownTest {

    private fun controller(grabber: FakeFrameGrabber, recorder: FakeFrameRecorder = FakeFrameRecorder()) =
        CameraController(
            imageFormat = ImageFormat.JPEG,
            directory = Directory.PICTURES,
            customGrabber = grabber,
            sessionDispatcher = Dispatchers.Unconfined,
            recorderFactory = { _, _ -> recorder },
        )

    private fun recordingConfig() = VideoConfiguration(
        enableAudio = false,
        outputDirectory = Files.createTempDirectory("camerak-teardown").toFile().absolutePath,
    )

    @Test
    fun stop_whileGrabIsInFlight_releasesTheGrabberOnlyAfterGrabReturns() {
        val fake = FakeFrameGrabber()
        val errors = CopyOnWriteArrayList<Throwable>()
        val frames = MutableSharedFlow<BufferedImage>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val scope = CoroutineScope(SupervisorJob())
        val sut = CameraGrabber(frames, { errors += it })
        try {
            sut.start(scope, fake)
            assertTrue(fake.firstGrabEntered.await(5, TimeUnit.SECONDS), "the grab loop never called grab()")

            // The loop is now blocked inside grab(), as it is between two camera frames.
            sut.stop()
            fake.openGate()

            assertTrue(fake.releasedLatch.await(5, TimeUnit.SECONDS), "the grabber was never released")
            assertEquals(emptyList(), fake.violations.toList())
            assertEquals(1, fake.releaseCount.get())
            assertEquals(emptyList(), errors.toList())
        } finally {
            fake.openGate()
            scope.cancel()
        }
    }

    @Test
    fun stopSessionThenCleanup_releasesTheGrabberExactlyOnce() {
        val fake = FakeFrameGrabber().apply { openGate() }
        val sut = controller(fake)

        sut.startSession()
        assertTrue(fake.firstGrabEntered.await(5, TimeUnit.SECONDS), "the grab loop never called grab()")

        // CameraKStateHolder.shutdown() calls exactly this pair.
        sut.stopSession()
        sut.cleanup()

        assertTrue(fake.releasedLatch.await(5, TimeUnit.SECONDS), "the grabber was never released")
        assertEquals(1, fake.releaseCount.get(), "release() count")
        assertEquals(1, fake.stopCount.get(), "stop() count")
        assertEquals(emptyList(), fake.violations.toList())
    }

    @Test
    fun stopRecording_whileRecordIsInFlight_stopsTheRecorderOnlyAfterRecordReturns() = runBlocking {
        val fake = FakeFrameGrabber().apply { openGate() }
        val recorder = FakeFrameRecorder()
        val sut = controller(fake, recorder)
        try {
            sut.startSession()
            sut.startRecording(recordingConfig())
            assertTrue(recorder.firstRecordEntered.await(5, TimeUnit.SECONDS), "nothing was recorded")

            // The encoder is busy with a frame. Stop the recording, and give a premature
            // recorder.stop() the chance to happen before the frame finishes.
            val stop = async(Dispatchers.IO) { sut.stopRecording() }
            recorder.stopCalled.await(1, TimeUnit.SECONDS)
            recorder.finishFirstRecord()

            val result = withTimeout(10_000) { stop.await() }
            assertIs<VideoCaptureResult.Success>(result)
            assertEquals(emptyList(), recorder.violations.toList())
            assertEquals(1, recorder.stopCount.get(), "recorder stop() count")
            assertEquals(1, recorder.releaseCount.get(), "recorder release() count")
        } finally {
            recorder.finishFirstRecord()
            sut.cleanup()
        }
    }

    @Test
    fun cleanup_whileRecordIsInFlight_returnsAtOnceAndStopsTheRecorderOnlyAfterRecordReturns() = runBlocking {
        val fake = FakeFrameGrabber().apply { openGate() }
        val recorder = FakeFrameRecorder()
        val sut = controller(fake, recorder)
        try {
            sut.startSession()
            sut.startRecording(recordingConfig())
            assertTrue(recorder.firstRecordEntered.await(5, TimeUnit.SECONDS), "nothing was recorded")

            // cleanup() is called from Compose's onDispose, so it must not wait for the encoder.
            sut.cleanup()
            assertTrue(recorder.isRecordInFlight, "cleanup() blocked until the in-flight record() returned")
            recorder.finishFirstRecord()

            assertTrue(recorder.releasedLatch.await(5, TimeUnit.SECONDS), "the recorder was never released")
            assertEquals(emptyList(), recorder.violations.toList())
            assertEquals(1, recorder.stopCount.get(), "recorder stop() count")
            assertEquals(1, recorder.releaseCount.get(), "recorder release() count")
            assertTrue(fake.releasedLatch.await(5, TimeUnit.SECONDS), "the grabber was never released")
        } finally {
            recorder.finishFirstRecord()
        }
    }
}

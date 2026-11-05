package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.video.VideoCaptureResult
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bytedeco.javacv.Frame
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * One producer owns the frame grabber: preview, still capture and recording all read its frames,
 * and nothing else ever calls grab(). [FakeFrameGrabber] has no internal locking, like the Windows
 * grabber, so a second caller shows up as an overlapping grab() or a grab() on another thread.
 *
 * Each test holds the producer inside grab() (the gate is closed, as between two camera frames),
 * starts the consumer under test, gives an out-of-band grab() a moment to overlap, then lets frames
 * flow.
 */
class DesktopSingleFrameProducerTest {

    private fun controller(grabber: FakeFrameGrabber, recorder: FakeFrameRecorder = FakeFrameRecorder()) =
        CameraController(
            imageFormat = ImageFormat.PNG,
            directory = Directory.PICTURES,
            customGrabber = grabber,
            sessionDispatcher = Dispatchers.Unconfined,
            recorderFactory = { _, _ -> recorder },
        )

    private fun assertSingleProducer(fake: FakeFrameGrabber) {
        assertEquals(1, fake.maxConcurrentGrabs.get(), "grab() calls that overlapped")
        assertEquals(1, fake.grabThreads.size, "threads that called grab(): ${fake.grabThreads}")
    }

    @Suppress("DEPRECATION")
    @Test
    fun grabCurrentFrame_whileThePreviewLoopRuns_returnsTheProducersFrameWithoutCallingGrab() {
        val fake = FakeFrameGrabber()
        val frames = MutableSharedFlow<BufferedImage>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val scope = CoroutineScope(SupervisorJob())
        val previewImage = CompletableDeferred<BufferedImage>()
        scope.launch(Dispatchers.Unconfined) { frames.collect { previewImage.complete(it) } }
        val sut = CameraGrabber(frames, { throw AssertionError("camera error", it) })
        try {
            sut.start(scope, fake)
            assertTrue(fake.firstGrabEntered.await(5, TimeUnit.SECONDS), "the grab loop never called grab()")

            var captured: BufferedImage? = null
            val caller = thread(name = CAPTURE_THREAD) { captured = sut.grabCurrentFrame() }
            fake.concurrentGrabEntered.await(1, TimeUnit.SECONDS)
            fake.openGate()
            caller.join(10_000)

            assertFalse(caller.isAlive, "grabCurrentFrame() did not return")
            val image = assertNotNull(captured, "grabCurrentFrame() returned no frame")
            assertFalse(CAPTURE_THREAD in fake.grabThreads, "grab() was called on the capture caller's thread")
            assertSingleProducer(fake)
            // The preview is still drawing from the grab loop's buffer; a capture must not share it.
            val preview = runBlocking { withTimeout(5_000) { previewImage.await() } }
            assertNotSame(preview, image, "the captured frame is the preview's reused buffer")
        } finally {
            fake.openGate()
            sut.stop()
            scope.cancel()
        }
    }

    @Test
    fun takePictureToFile_whileThePreviewLoopRuns_capturesTheProducersFrameWithoutCallingGrab() = runBlocking {
        val fake = FakeFrameGrabber()
        val sut = controller(fake)
        var savedPath: String? = null
        try {
            sut.startSession()
            assertTrue(fake.firstGrabEntered.await(5, TimeUnit.SECONDS), "the grab loop never called grab()")

            val capture = async(Dispatchers.IO) { sut.takePictureToFile() }
            fake.concurrentGrabEntered.await(1, TimeUnit.SECONDS)
            fake.openGate()

            val result = withTimeout(10_000) { capture.await() }
            val saved = assertIs<ImageCaptureResult.SuccessWithFile>(result)
            savedPath = saved.filePath
            val image = assertNotNull(ImageIO.read(File(saved.filePath)), "the capture is not a readable image")
            assertEquals(64 to 48, image.width to image.height)
            assertSingleProducer(fake)
        } finally {
            fake.openGate()
            sut.cleanup()
            savedPath?.let { File(it).delete() }
        }
    }

    @Test
    fun recording_whileThePreviewLoopRuns_recordsTheProducersFramesWithoutCallingGrab() = runBlocking {
        val fake = FakeFrameGrabber()
        val recorder = FakeFrameRecorder().apply { finishFirstRecord() }
        val sut = controller(fake, recorder)
        try {
            sut.startSession()
            assertTrue(fake.firstGrabEntered.await(5, TimeUnit.SECONDS), "the grab loop never called grab()")

            sut.startRecording(
                VideoConfiguration {
                    enableAudio = false
                    outputDirectory = Files.createTempDirectory("camerak-producer").toFile().absolutePath
                },
            )
            fake.concurrentGrabEntered.await(1, TimeUnit.SECONDS)
            fake.openGate()

            assertTrue(recorder.fiveFramesRecorded.await(10, TimeUnit.SECONDS), "the recorder was not fed frames")
            assertIs<VideoCaptureResult.Success>(withTimeout(10_000) { sut.stopRecording() })
            assertSingleProducer(fake)
        } finally {
            fake.openGate()
            sut.cleanup()
        }
    }

    @Test
    fun aGrabberThatReturnsNoFrame_endsTheRunWithAnErrorInsteadOfSpinning() = assertRunEndsWithAnError { null }

    @Test
    fun aGrabberThatReturnsOnlyFramesWithoutAnImage_endsTheRunWithAnErrorInsteadOfSpinning() =
        assertRunEndsWithAnError { Frame() }

    // grab() returns at once here, as an FFmpeg grabber does at end of stream or on a lost device, so
    // nothing paces the loop: unless it ends, it spins the grab thread at full CPU forever.
    private fun assertRunEndsWithAnError(nextFrame: (Frame) -> Frame?) {
        val fake = FakeFrameGrabber(nextFrame = nextFrame).apply { openGate() }
        val errors = CopyOnWriteArrayList<Throwable>()
        val frames = MutableSharedFlow<BufferedImage>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val scope = CoroutineScope(SupervisorJob())
        val sut = CameraGrabber(frames, { errors += it })
        try {
            sut.start(scope, fake)

            assertTrue(
                fake.releasedLatch.await(5, TimeUnit.SECONDS),
                "the grab loop kept polling a grabber with no frames",
            )
            assertEquals(1, errors.size, "errors reported for the ended run: $errors")
            assertEquals(emptyList(), fake.violations.toList())
        } finally {
            sut.stop()
            scope.cancel()
        }
    }

    // W6 G4's rule (w7 decisions, D3' carry-over 3): a recording the session's stop ends with a kept,
    // playable file is a 1.x Success with that path, as on Android.
    @Test
    fun recording_whenTheCameraStops_returnsTheKeptFile() = runBlocking {
        val fake = FakeFrameGrabber().apply { openGate() }
        val recorder = FakeFrameRecorder().apply { finishFirstRecord() }
        val sut = controller(fake, recorder)
        try {
            sut.startSession()
            val path = sut.startRecording(recordingConfig())
            assertTrue(recorder.fiveFramesRecorded.await(10, TimeUnit.SECONDS), "the recorder was not fed frames")

            // The producer ends while the recording runs: the recording must not sit waiting for
            // frames that will never come.
            sut.stopSession()
            assertTrue(recorder.releasedLatch.await(5, TimeUnit.SECONDS), "the recording outlived its camera")

            val kept = assertIs<VideoCaptureResult.Success>(withTimeout(10_000) { sut.stopRecording() })
            assertEquals(path, kept.filePath, "the kept recording's path")
            assertTrue(File(kept.filePath).exists(), "the kept recording's file")
            assertTrue(recorder.recordCount.get() >= 5, "frames the kept file holds: ${recorder.recordCount.get()}")
            assertEquals(emptyList(), recorder.violations.toList())
        } finally {
            sut.cleanup()
        }
    }

    @Test
    fun recording_withoutARunningSession_endsAndReportsAnError() = runBlocking {
        val fake = FakeFrameGrabber().apply { openGate() }
        val recorder = FakeFrameRecorder().apply { finishFirstRecord() }
        val sut = controller(fake, recorder)
        try {
            sut.startRecording(recordingConfig())

            assertTrue(
                recorder.releasedLatch.await(5, TimeUnit.SECONDS),
                "the recording waited for a camera that never ran",
            )
            assertIs<VideoCaptureResult.Error>(withTimeout(10_000) { sut.stopRecording() })
            assertEquals(0, recorder.recordCount.get(), "frames recorded without a camera")
        } finally {
            sut.cleanup()
        }
    }

    private fun recordingConfig() = VideoConfiguration {
        enableAudio = false
        outputDirectory = Files.createTempDirectory("camerak-producer").toFile().absolutePath
    }

    private companion object {
        const val CAPTURE_THREAD = "camerak-test-capture-caller"
    }
}

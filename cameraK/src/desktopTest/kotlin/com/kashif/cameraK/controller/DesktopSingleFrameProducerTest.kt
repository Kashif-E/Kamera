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
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
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
                VideoConfiguration(
                    enableAudio = false,
                    outputDirectory = Files.createTempDirectory("camerak-producer").toFile().absolutePath,
                ),
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

    private companion object {
        const val CAPTURE_THREAD = "camerak-test-capture-caller"
    }
}

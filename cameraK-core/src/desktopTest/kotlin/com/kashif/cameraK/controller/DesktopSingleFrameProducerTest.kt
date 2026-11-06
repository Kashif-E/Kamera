@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineRecordingEvent
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bytedeco.javacv.Frame
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * One producer owns the frame grabber: preview, still capture and recording all read its frames,
 * and nothing else ever calls grab(). [FakeFrameGrabber] has no internal locking, like the Windows
 * grabber, so a second caller shows up as an overlapping grab() or a grab() on another thread.
 *
 * Each test holds the producer inside grab() (the gate is closed, as between two camera frames),
 * starts the consumer under test, gives an out-of-band grab() a moment to overlap, then lets frames
 * flow. The engine runs on real threads here, because the fake's overlap check needs them.
 */
class DesktopSingleFrameProducerTest {
    private val home: File = Files.createTempDirectory("camerak-producer").toFile()

    private val desired = DesiredHardware(
        selection = CameraSelection.ByLens(CameraLens.BACK, CameraDeviceType.DEFAULT),
        flashMode = FlashMode.OFF,
        torchMode = TorchMode.OFF,
        zoomRatio = 1f,
        targetOrientation = null,
    )

    private fun engine(grabber: FakeFrameGrabber, recorder: FakeFrameRecorder = FakeFrameRecorder()) =
        DesktopCameraEngine(
            // A 4:3 configuration keeps the fake's 64 x 48 frames whole: the engine crops to the ratio.
            CameraConfiguration {
                imageFormat = ImageFormat.PNG
                aspectRatio = AspectRatio.RATIO_4_3
            },
            CameraKEnvironment(CameraKDispatchers(main = Dispatchers.Default, default = Dispatchers.Default)),
            CustomGrabberSource(grabber),
            authorization = { PermissionStatus.Granted },
            media = DesktopMedia(userHome = home, recorderFactory = { _, _ -> recorder }),
        )

    // Lets the camera deliver the one frame the start waits for, then holds the producer in its next grab().
    private suspend fun open(sut: DesktopCameraEngine, fake: FakeFrameGrabber) {
        fake.allowGrabs(1)
        assertEquals(CameraKResult.Success(Unit), withTimeout(10_000) { sut.start(desired) }, "start")
    }

    private fun assertSingleProducer(fake: FakeFrameGrabber) {
        assertEquals(1, fake.maxConcurrentGrabs.get(), "grab() calls that overlapped")
        assertEquals(1, fake.grabThreads.size, "threads that called grab(): ${fake.grabThreads}")
    }

    @Test
    fun capture_whileThePreviewLoopRuns_capturesTheProducersFrameWithoutCallingGrab() = runBlocking {
        val fake = FakeFrameGrabber()
        val sut = engine(fake)
        try {
            open(sut, fake)

            val capture = async(Dispatchers.IO) { sut.capture() }
            fake.concurrentGrabEntered.await(1, TimeUnit.SECONDS)
            fake.openGate()

            val image = assertIs<CameraKResult.Success<CapturedImage>>(withTimeout(10_000) { capture.await() }).value
            val read = assertNotNull(ImageIO.read(File(image.path)), "the capture is not a readable image")
            assertEquals(64 to 48, read.width to read.height)
            assertSingleProducer(fake)
        } finally {
            fake.openGate()
            withTimeout(10_000) { sut.closeAndJoin() }
            home.deleteRecursively()
        }
    }

    @Test
    fun recording_whileThePreviewLoopRuns_recordsTheProducersFramesWithoutCallingGrab() = runBlocking {
        val fake = FakeFrameGrabber()
        val recorder = FakeFrameRecorder().apply { finishFirstRecord() }
        val sut = engine(fake, recorder)
        try {
            open(sut, fake)

            val start = async(Dispatchers.IO) { sut.startRecording(recordingConfig()) }
            fake.concurrentGrabEntered.await(1, TimeUnit.SECONDS)
            fake.openGate()
            assertIs<CameraKResult.Success<String>>(withTimeout(10_000) { start.await() }, "startRecording")

            assertTrue(recorder.fiveFramesRecorded.await(10, TimeUnit.SECONDS), "the recorder was not fed frames")
            assertIs<CameraKResult.Success<RecordedVideo>>(withTimeout(10_000) { sut.stopRecording() })
            assertSingleProducer(fake)
        } finally {
            fake.openGate()
            withTimeout(10_000) { sut.closeAndJoin() }
            home.deleteRecursively()
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

    // A recording the camera's stop ends keeps its playable file: its one Finalized is a Success with
    // that path (W6 G4, the rule on Android too).
    @Test
    fun recording_whenTheCameraStops_finalizesWithTheKeptFile() = runBlocking {
        val fake = FakeFrameGrabber()
        val recorder = FakeFrameRecorder().apply { finishFirstRecord() }
        val sut = engine(fake, recorder)
        try {
            open(sut, fake)
            fake.openGate()
            val finalized = async(Dispatchers.IO) {
                sut.recordingEvents.filterIsInstance<EngineRecordingEvent.Finalized>().first()
            }
            val path = assertIs<CameraKResult.Success<String>>(sut.startRecording(recordingConfig())).value
            assertTrue(recorder.fiveFramesRecorded.await(10, TimeUnit.SECONDS), "the recorder was not fed frames")

            // The camera stops while the recording runs: the recording must not sit waiting for
            // frames that will never come.
            withTimeout(10_000) { sut.stop() }
            assertTrue(recorder.releasedLatch.await(5, TimeUnit.SECONDS), "the recording outlived its camera")

            val event = withTimeout(10_000) { finalized.await() }
            val kept = assertIs<CameraKResult.Success<RecordedVideo>>(event.result).value
            assertEquals(RecordingEndReason.SHUTDOWN, event.reason)
            assertEquals(path, kept.path, "the kept recording's path")
            assertTrue(File(kept.path).exists(), "the kept recording's file")
            assertTrue(recorder.recordCount.get() >= 5, "frames the kept file holds: ${recorder.recordCount.get()}")
            assertEquals(emptyList(), recorder.violations.toList())
        } finally {
            fake.openGate()
            withTimeout(10_000) { sut.closeAndJoin() }
            home.deleteRecursively()
        }
    }

    @Test
    fun recording_withoutARunningSession_isNotReadyAndRecordsNothing() = runBlocking {
        val fake = FakeFrameGrabber().apply { openGate() }
        val recorder = FakeFrameRecorder().apply { finishFirstRecord() }
        val sut = engine(fake, recorder)
        try {
            assertEquals(CameraKResult.Failure(CameraKError.NotReady), sut.startRecording(recordingConfig()))
            assertEquals(0, recorder.recordCount.get(), "frames recorded without a camera")
        } finally {
            withTimeout(10_000) { sut.closeAndJoin() }
            home.deleteRecursively()
        }
    }

    private fun recordingConfig() = VideoConfiguration {
        enableAudio = false
        outputDirectory = Files.createTempDirectory("camerak-producer").toFile().absolutePath
    }
}

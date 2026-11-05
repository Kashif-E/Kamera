package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.video.VideoCaptureResult
import com.kashif.cameraK.video.VideoConfiguration
import com.kashif.cameraK.video.VideoQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Desktop recordings play in real time: each frame reaches the encoder with the time it was
 * captured, measured from the start of the recording with paused time left out, at the camera's
 * own frame size, with no audio track the desktop cannot fill, and the recording's frame converter
 * is closed when it ends.
 */
class DesktopRecordingPipelineTest {

    private val outputDirectory = Files.createTempDirectory("camerak-recording").toFile()

    private fun config(quality: VideoQuality = VideoQuality.FHD) = VideoConfiguration {
        this.quality = quality
        enableAudio = false
        outputDirectory = this@DesktopRecordingPipelineTest.outputDirectory.absolutePath
    }

    @Test
    fun eachFrame_isTimestampedWithItsCaptureTimeSinceTheStart_andThePauseIsLeftOut() = runBlocking<Unit> {
        val clock = AtomicLong(0)
        val grabs = Semaphore(0)
        // Every grab is one camera frame, FRAME_NANOS after the previous one.
        val fake = FakeFrameGrabber { frame ->
            clock.addAndGet(FRAME_NANOS)
            grabs.release()
            frame
        }
        val recorder = FakeFrameRecorder().apply { finishFirstRecord() }
        val sut = CameraController(
            imageFormat = ImageFormat.JPEG,
            directory = Directory.PICTURES,
            customGrabber = fake,
            sessionDispatcher = Dispatchers.Unconfined,
            recorderFactory = { _, _ -> recorder },
            nanoClock = clock::get,
        )
        try {
            sut.startSession()
            assertTrue(fake.firstGrabEntered.await(5, TimeUnit.SECONDS), "the grab loop never called grab()")
            sut.startRecording(config())

            // Let the camera deliver one frame at a time until the recording takes one (frames
            // grabbed before it subscribed are not part of it), then two more.
            var tries = 0
            do {
                fake.allowGrabs(1)
            } while (!recorder.awaitRecords(1, 200) && ++tries < 25)
            recordFrames(fake, recorder, 2)

            sut.pauseRecording()
            fake.allowGrabs(PAUSED_FRAMES)
            assertTrue(grabs.tryAcquire(PAUSED_FRAMES + 3 + tries, 5, TimeUnit.SECONDS), "the camera stopped")
            sut.resumeRecording()
            recordFrames(fake, recorder, 3)

            val result = assertIs<VideoCaptureResult.Success>(withTimeout(10_000) { sut.stopRecording() })
            val timestamps = recorder.timestamps.toList()
            assertEquals(6, timestamps.size, "frames recorded: $timestamps")
            assertTrue(timestamps.first() > 0, "the first frame's timestamp: ${timestamps.first()}")
            // One frame interval apart, across the pause too: the paused time is not in the video.
            assertEquals(List(5) { FRAME_MICROS }, timestamps.zipWithNext { a, b -> b - a }, "timestamps $timestamps")
            assertEquals(timestamps.last() / 1_000, result.durationMs, "duration of the recording without the pause")
        } finally {
            fake.openGate()
            sut.cleanup()
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun aCameraFasterThanTheRecordingsFrameRate_neverGivesTwoFramesTheSameFrameNumber() = runBlocking<Unit> {
        val clock = AtomicLong(0)
        // A 60 fps camera: two frames for every frame slot of the 30 fps recording.
        val fake = FakeFrameGrabber { frame ->
            clock.addAndGet(FAST_FRAME_NANOS)
            frame
        }
        val recorder = FakeFrameRecorder().apply { finishFirstRecord() }
        val sut = CameraController(
            imageFormat = ImageFormat.JPEG,
            directory = Directory.PICTURES,
            customGrabber = fake,
            sessionDispatcher = Dispatchers.Unconfined,
            recorderFactory = { _, _ -> recorder },
            nanoClock = clock::get,
        )
        try {
            sut.startSession()
            assertTrue(fake.firstGrabEntered.await(5, TimeUnit.SECONDS), "the grab loop never called grab()")
            sut.startRecording(config())
            var tries = 0
            do {
                fake.allowGrabs(1)
            } while (!recorder.awaitRecords(1, 200) && ++tries < 25)
            // One camera frame at a time, giving the recording time to take each one.
            repeat(FAST_FRAMES) {
                fake.allowGrabs(1)
                recorder.awaitRecords(1, 200)
            }
            withTimeout(10_000) { sut.stopRecording() }

            // The encoder turns each timestamp into a frame number at its frame rate, as
            // FFmpegFrameRecorder.setTimestamp does; a repeated number is a duplicate pts it rejects.
            val frameNumbers = recorder.timestamps.map { Math.round(it * recorder.frameRate / 1_000_000) }
            assertTrue(frameNumbers.size >= FAST_FRAMES / 4, "frames recorded: $frameNumbers")
            assertTrue(
                frameNumbers.zipWithNext().all { (a, b) -> b > a },
                "frame numbers are not strictly increasing: $frameNumbers",
            )
        } finally {
            fake.openGate()
            sut.cleanup()
            outputDirectory.deleteRecursively()
        }
    }

    private fun recordFrames(fake: FakeFrameGrabber, recorder: FakeFrameRecorder, count: Int) = repeat(count) {
        fake.allowGrabs(1)
        assertTrue(recorder.awaitRecords(1, 5_000), "the recording missed a frame")
    }

    @Test
    fun theRecorder_isSizedToTheCamerasFrames() = runBlocking<Unit> {
        val fake = FakeFrameGrabber(width = 64, height = 48).apply { openGate() }
        val recorder = FakeFrameRecorder().apply { finishFirstRecord() }
        val sut = CameraController(
            imageFormat = ImageFormat.JPEG,
            directory = Directory.PICTURES,
            customGrabber = fake,
            sessionDispatcher = Dispatchers.Unconfined,
            recorderFactory = { _, _ -> recorder },
        )
        try {
            sut.startSession()
            withTimeout(5_000) { sut.frameFlow.first() }
            sut.startRecording(config(VideoQuality.FHD))

            assertEquals(64 to 48, recorder.sizeAtStart, "the recorder's frame size when it started")
            withTimeout(10_000) { sut.stopRecording() }
        } finally {
            sut.cleanup()
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun aRecordingThatAsksForAudio_declaresNoAudioTrack() {
        val recorder = createFrameRecorder(
            File(outputDirectory, "audio.mp4").absolutePath,
            VideoConfiguration { enableAudio = true },
        )
        try {
            assertEquals(0, (recorder as FFmpegFrameRecorder).audioChannels, "audio channels with nothing to record")
        } finally {
            recorder.release()
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun theRecordingsFrameConverter_isClosedWhenTheRecordingStops() = runBlocking<Unit> {
        val fake = FakeFrameGrabber().apply { openGate() }
        val recorder = FakeFrameRecorder().apply { finishFirstRecord() }
        val closes = AtomicInteger(0)
        val sut = CameraController(
            imageFormat = ImageFormat.JPEG,
            directory = Directory.PICTURES,
            customGrabber = fake,
            sessionDispatcher = Dispatchers.Unconfined,
            recorderFactory = { _, _ -> recorder },
            recordingConverter = {
                object : Java2DFrameConverter() {
                    override fun close() {
                        closes.incrementAndGet()
                        super.close()
                    }
                }
            },
        )
        try {
            sut.startSession()
            sut.startRecording(config())
            assertTrue(recorder.fiveFramesRecorded.await(10, TimeUnit.SECONDS), "the recorder was not fed frames")
            withTimeout(10_000) { sut.stopRecording() }

            assertEquals(1, closes.get(), "frame converter close() count")
        } finally {
            sut.cleanup()
            outputDirectory.deleteRecursively()
        }
    }

    private companion object {
        const val FRAME_NANOS = 40_000_000L
        const val FRAME_MICROS = FRAME_NANOS / 1_000
        const val PAUSED_FRAMES = 25
        const val FAST_FRAME_NANOS = 16_000_000L
        const val FAST_FRAMES = 20
    }
}

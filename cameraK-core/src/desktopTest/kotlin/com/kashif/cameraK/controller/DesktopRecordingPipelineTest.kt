@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import com.kashif.cameraK.video.VideoQuality
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Desktop recordings play in real time: each frame reaches the encoder with the time it was
 * captured, measured from the start of the recording with paused time left out, at the camera's
 * own frame size, and the recording leaves nothing but its recorder to release when it ends. Over
 * the engine's rig, whose camera delivers a frame when the case says so, at the time the case sets.
 */
class DesktopRecordingPipelineTest {
    // The engine crops frames to the configured aspect ratio; the rig's camera is 4:3 (64 x 48), so a
    // 4:3 configuration keeps its frames whole.
    private val wholeFrames = CameraConfiguration { aspectRatio = AspectRatio.RATIO_4_3 }

    private fun config(quality: VideoQuality = VideoQuality.FHD) = VideoConfiguration {
        this.quality = quality
        enableAudio = false
    }

    @Test
    fun eachFrame_isTimestampedWithItsCaptureTimeSinceTheStart_andThePauseIsLeftOut() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        try {
            steps.open(sut)
            steps.at(START_MILLIS)
            steps.startRecording(sut, config())
            var millis = START_MILLIS
            repeat(3) {
                millis += FRAME_MILLIS
                steps.frameAt(millis)
            }

            assertEquals(CameraKResult.Success(Unit), sut.pauseRecording())
            repeat(PAUSED_FRAMES) {
                millis += FRAME_MILLIS
                steps.frameAt(millis)
            }
            assertEquals(CameraKResult.Success(Unit), sut.resumeRecording())
            repeat(3) {
                millis += FRAME_MILLIS
                steps.frameAt(millis)
            }

            val video = assertIs<CameraKResult.Success<RecordedVideo>>(steps.stopRecording(sut)).value
            val timestamps = rig.recorders.single().timestamps.toList()
            assertEquals(6, timestamps.size, "frames recorded: $timestamps")
            assertTrue(timestamps.first() > 0, "the first frame's timestamp: ${timestamps.first()}")
            // One frame interval apart, across the pause too: the paused time is not in the video.
            assertEquals(List(5) { FRAME_MICROS }, timestamps.zipWithNext { a, b -> b - a }, "timestamps $timestamps")
            assertEquals(
                timestamps.last() / 1_000,
                video.duration.inWholeMilliseconds,
                "duration of the recording without the pause",
            )
        } finally {
            sut.close()
            rig.settle()
        }
    }

    @Test
    fun aCameraFasterThanTheRecordingsFrameRate_neverGivesTwoFramesTheSameFrameNumber() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        try {
            steps.open(sut)
            steps.at(START_MILLIS)
            steps.startRecording(sut, config())
            // A 60 fps camera: two frames for every frame slot of the 30 fps recording.
            repeat(FAST_FRAMES) { steps.frameAt(START_MILLIS + (it + 1) * FAST_FRAME_MILLIS) }
            assertIs<CameraKResult.Success<RecordedVideo>>(steps.stopRecording(sut))

            // The encoder turns each timestamp into a frame number at its frame rate, as
            // FFmpegFrameRecorder.setTimestamp does; a repeated number is a duplicate pts it rejects.
            val recorder = rig.recorders.single()
            val frameNumbers = recorder.timestamps.map { Math.round(it * recorder.frameRate / 1_000_000) }
            assertTrue(frameNumbers.size >= FAST_FRAMES / 4, "frames recorded: $frameNumbers")
            assertTrue(
                frameNumbers.zipWithNext().all { (a, b) -> b > a },
                "frame numbers are not strictly increasing: $frameNumbers",
            )
        } finally {
            sut.close()
            rig.settle()
        }
    }

    @Test
    fun theRecorder_isSizedToTheCamerasFrames() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine(wholeFrames)
        try {
            steps.open(sut)
            steps.frameAt(START_MILLIS)
            steps.startRecording(sut, config(VideoQuality.FHD))

            assertEquals(64 to 48, rig.recorders.single().sizeAtStart, "the recorder's frame size when it started")
            steps.frameAt(START_MILLIS + FRAME_MILLIS)
            assertIs<CameraKResult.Success<RecordedVideo>>(steps.stopRecording(sut))
        } finally {
            sut.close()
            rig.settle()
        }
    }

    @Test
    fun theRecordingsFrames_holdNoNativeMemory_andItsRecorderIsReleasedOnceWhenItStops() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        try {
            steps.open(sut)
            steps.at(START_MILLIS)
            steps.startRecording(sut, config())
            repeat(5) { steps.frameAt(START_MILLIS + (it + 1) * FRAME_MILLIS) }
            assertIs<CameraKResult.Success<RecordedVideo>>(steps.stopRecording(sut))

            // The frames wrap the producer's slots, so the recording has no converter or native
            // frame of its own to close: the recorder is the one thing it releases.
            val recorder = rig.recorders.single()
            assertEquals(5, recorder.buffers.size, "frames recorded")
            assertTrue(recorder.buffers.none { it.isDirect }, "a recorded frame held native memory")
            assertEquals(1, recorder.releaseCount.get(), "recorder release() count")
        } finally {
            sut.close()
            rig.settle()
        }
    }

    private companion object {
        const val START_MILLIS = 1_000L
        const val FRAME_MILLIS = 40L
        const val FRAME_MICROS = FRAME_MILLIS * 1_000
        const val PAUSED_FRAMES = 25
        const val FAST_FRAME_MILLIS = 16L
        const val FAST_FRAMES = 20
    }
}

@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.EngineRecordingEvent
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The desktop engine enforces both recording limits itself (§5): `maxDuration` on the common
 * `RecordingClock`, so paused time does not count, and `maxFileSizeBytes` on the file's length after
 * each frame. Each ends the recording through the one stop path, as `Finalized(Success, MAX_DURATION)`
 * or `Finalized(Success, MAX_FILE_SIZE)` with the file kept; nothing is left to stop afterwards.
 */
class DesktopRecordingLimitsTest {
    @Test
    fun maxDurationFinalizesThroughTheSinglePathPauseAware() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        try {
            steps.open(sut)
            val events = steps.events(sut)
            steps.at(1_000)
            val path = steps.startRecording(sut, steps.video(maxDurationMillis = 200))
            steps.frameAt(1_040)
            steps.frameAt(1_080)
            steps.at(1_100)
            assertEquals(CameraKResult.Success(Unit), sut.pauseRecording())
            // 800 ms paused: far past the limit on the wall clock, none of it recorded.
            for (millis in 1_140L..1_860L step 40L) steps.frameAt(millis)
            steps.at(1_900)
            assertEquals(CameraKResult.Success(Unit), sut.resumeRecording())
            steps.frameAt(1_940)
            steps.frameAt(1_980)
            assertEquals(emptyList(), events.filterIsInstance<EngineRecordingEvent.Finalized>(), "ended before 200 ms")

            // 220 ms recorded: past the limit, so this frame is not written and the recording ends.
            steps.frameAt(2_020)

            val recorder = rig.recorders.single()
            assertEquals(listOf(40_000L, 80_000L, 140_000L, 180_000L), recorder.timestamps.toList(), "frames written")
            val finalized = events.filterIsInstance<EngineRecordingEvent.Finalized>().single()
            assertEquals(RecordingEndReason.MAX_DURATION, finalized.reason)
            val video = assertIs<CameraKResult.Success<RecordedVideo>>(finalized.result).value
            assertEquals(path, video.path)
            assertEquals(180.milliseconds, video.duration, "the recorded time, without the pause")
            assertTrue(recorder.file.exists(), "the file of a recording that reached its limit")
            assertEquals(1, recorder.stopCount.get(), "recorder stop() count")
            assertEquals(1, recorder.releaseCount.get(), "recorder release() count")
            val stop = assertIs<CameraKResult.Failure>(sut.stopRecording(), "a stop after the limit ended it").error
            assertEquals(CameraOperation.RECORD_STOP, assertIs<CameraKError.InvalidState>(stop).operation)
        } finally {
            sut.close()
            rig.settle()
        }
    }

    @Test
    fun maxFileSizeFinalizes() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        try {
            steps.open(sut)
            val events = steps.events(sut)
            steps.at(1_000)
            // Each scripted frame adds 1,000 bytes to the file.
            val path = steps.startRecording(sut, steps.video(maxFileSizeBytes = 2_500))
            steps.frameAt(1_040)
            steps.frameAt(1_080)
            assertEquals(emptyList(), events.filterIsInstance<EngineRecordingEvent.Finalized>(), "ended at 2,000 bytes")

            steps.frameAt(1_120)
            steps.frameAt(1_160)

            val recorder = rig.recorders.single()
            assertEquals(3, recorder.recordCount.get(), "frames written: the third reached the limit")
            val finalized = events.filterIsInstance<EngineRecordingEvent.Finalized>().single()
            assertEquals(RecordingEndReason.MAX_FILE_SIZE, finalized.reason)
            assertEquals(path, assertIs<CameraKResult.Success<RecordedVideo>>(finalized.result).value.path)
            assertEquals(3_000L, recorder.file.length(), "the kept file")
            assertEquals(1, recorder.releaseCount.get(), "recorder release() count")
        } finally {
            sut.close()
            rig.settle()
        }
    }
}

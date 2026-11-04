@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.EngineRecordingEvent
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pause and resume run on the common `RecordingClock` (§2.4 desktop). Each returns `Success` once the
 * clock has paused or resumed and emits the engine's `Paused` or `Resumed`; frames grabbed while
 * paused are not written, and the paused time is left out of every later timestamp and of the
 * recording's duration.
 */
class DesktopPauseResumeTest {
    @Test
    fun pauseAndResumeEmitPausedAndResumedAndThePauseIsLeftOutOfTheFile() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        try {
            steps.open(sut)
            val events = steps.events(sut)
            steps.at(1_000)
            val path = steps.startRecording(sut)
            steps.frameAt(1_040)

            steps.at(1_060)
            assertEquals(CameraKResult.Success(Unit), sut.pauseRecording())
            steps.frameAt(1_100)
            steps.frameAt(1_140)
            steps.at(1_200)
            assertEquals(CameraKResult.Success(Unit), sut.resumeRecording())
            // 140 ms paused: this frame is 100 ms into the recording.
            steps.frameAt(1_240)
            val video = assertIs<CameraKResult.Success<RecordedVideo>>(steps.stopRecording(sut)).value

            assertEquals(listOf(40_000L, 100_000L), rig.recorders.single().timestamps.toList(), "frames written")
            assertEquals(100.milliseconds, video.duration, "the recording's duration without the pause")
            assertEquals(
                listOf(
                    EngineRecordingEvent.Started(path, hasAudio = false),
                    EngineRecordingEvent.Paused,
                    EngineRecordingEvent.Resumed,
                    EngineRecordingEvent.Finalized(CameraKResult.Success(video), RecordingEndReason.USER),
                ),
                events.toList(),
            )
        } finally {
            sut.close()
            rig.settle()
        }
    }
}

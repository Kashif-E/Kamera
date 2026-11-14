@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.EngineRecordingEvent
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A desktop recording with audio writes the microphone's samples on the recording's own thread,
 * aligned to the video's clock: late audio is preceded by silence, audio captured while paused is
 * left out, and a microphone that fails ends the recording as INTERRUPTED with what it recorded kept.
 * Over the engine's rig, with 48 kHz mono chunks of 20 ms (960 samples) captured at the times set.
 */
class DesktopRecordingAudioTest {
    private class Recording(
        val steps: DesktopRecordingSteps,
        val sut: DesktopCameraEngine,
        val microphone: FakeMicrophone,
        val events: List<EngineRecordingEvent>,
    ) {
        val recorder: ScriptedFrameRecorder get() = steps.rig.recorders.single()
    }

    // Opens the camera, then starts a recording with audio at 1000 ms on the rig's clock.
    private suspend fun TestScope.recordingWithAudio(microphone: FakeMicrophone = FakeMicrophone()): Recording {
        val rig = DesktopEngineRig(testScheduler)
        rig.microphone = MicrophoneOpener { microphone }
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        steps.open(sut)
        val events = steps.events(sut)
        steps.at(1_000)
        steps.startRecording(sut, steps.video().copy { enableAudio = true })
        return Recording(steps, sut, microphone, events)
    }

    private suspend fun Recording.finish(): CameraKResult<RecordedVideo> = try {
        steps.stopRecording(sut)
    } finally {
        sut.close()
        steps.rig.settle()
    }

    @Test
    fun samplesReachTheRecorderAndTheVideoHasAudio() = runTest {
        val recording = recordingWithAudio()
        with(recording) {
            microphone.deliver(1_001)
            microphone.deliver(1_021)
            steps.frameAt(1_030)
            microphone.deliver(1_041)
            steps.frameAt(1_050)

            val video = assertIs<CameraKResult.Success<RecordedVideo>>(finish()).value

            assertEquals(2_880L, recorder.samplesWritten.get(), "three chunks of 960")
            assertTrue(video.hasAudio, "RecordedVideo.hasAudio")
            assertTrue(assertIs<EngineRecordingEvent.Started>(events.first()).hasAudio, "Started.hasAudio")
            assertEquals(listOf(48_000 to 1), steps.rig.recorderAudio.toList(), "the recorder's audio format")
            assertEquals(setOf(48_000 to 1), recorder.audioFormats.toSet())
        }
    }

    @Test
    fun audioThatStartsLateIsPrecededBySilence() = runTest {
        val recording = recordingWithAudio()
        with(recording) {
            // macOS delivers nothing while its prompt is up: the first samples come 100 ms in.
            microphone.deliver(1_100)
            steps.frameAt(1_120)

            assertIs<CameraKResult.Success<RecordedVideo>>(finish())

            assertEquals(4_800L + 960L, recorder.samplesWritten.get(), "100 ms of silence, then the chunk")
        }
    }

    @Test
    fun samplesCapturedWhilePausedAreDropped() = runTest {
        val recording = recordingWithAudio()
        with(recording) {
            microphone.deliver(1_001)
            microphone.deliver(1_021)
            steps.frameAt(1_030)
            steps.at(1_041)
            assertEquals(CameraKResult.Success(Unit), sut.pauseRecording())
            microphone.deliver(1_050)
            microphone.deliver(1_070)
            steps.at(1_100)
            assertEquals(CameraKResult.Success(Unit), sut.resumeRecording())
            microphone.deliver(1_101)
            steps.frameAt(1_110)

            assertIs<CameraKResult.Success<RecordedVideo>>(finish())

            assertEquals(2_880L, recorder.samplesWritten.get(), "the two chunks before the pause and the one after")
        }
    }

    @Test
    fun aMicrophoneThatFailsEndsTheRecordingInterruptedAndKeepsTheFile() = runTest {
        val recording = recordingWithAudio(FakeMicrophone(failAfter = 2))
        with(recording) {
            microphone.deliver(1_001)
            steps.frameAt(1_010)
            microphone.deliver(1_021)
            // The reader fails on its next read; the recording sees it at the next frame. The failure is
            // set on the reader's thread a moment after that read, so a later frame may be the one.
            var at = 1_030L
            while (events.none { it is EngineRecordingEvent.Finalized } && at < 1_200) {
                steps.frameAt(at)
                at += 10
            }

            val finalized = events.filterIsInstance<EngineRecordingEvent.Finalized>().single()
            assertEquals(RecordingEndReason.INTERRUPTED, finalized.reason)
            val video = assertIs<CameraKResult.Success<RecordedVideo>>(finalized.result).value
            assertTrue(recorder.file.exists(), "the playable file was deleted")
            assertTrue(video.hasAudio)
            assertEquals(0L, microphone.closed.count, "the microphone was left open")
            sut.close()
            steps.rig.settle()
        }
    }

    @Test
    fun stopClosesTheMicrophoneAndOnlyTheRecordingsThreadWritesAudio() = runTest {
        val recording = recordingWithAudio()
        with(recording) {
            microphone.deliver(1_001)
            steps.frameAt(1_010)
            assertTrue(microphone.readBlocked.await(5, TimeUnit.SECONDS), "the reader never waited for a chunk")

            assertIs<CameraKResult.Success<RecordedVideo>>(finish())

            assertEquals(0L, microphone.closed.count, "stop left the microphone open")
            assertEquals(960L, recorder.samplesWritten.get())
            val recorderThreads = recorder.videoThreads.toSet()
            assertTrue(
                recorder.audioThreads.isNotEmpty() && recorder.audioThreads.all {
                    it in recorderThreads
                },
                "audio written off the recording's thread: ${recorder.audioThreads} vs $recorderThreads",
            )
        }
    }
}

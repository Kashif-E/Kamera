@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.capture.RecordedVideo
import dev.kashifmehmood.camerak.engine.EngineRecordingEvent
import dev.kashifmehmood.camerak.error.CameraKError
import dev.kashifmehmood.camerak.error.CameraOperation
import dev.kashifmehmood.camerak.recording.RecordingEndReason
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The Finalized contract of `CameraEngine.recordingEvents`, pinned on the desktop engine until C1'
 * adds the kit case (w6 decisions): every recording the engine started yields exactly one
 * `Finalized`, in start order, whether the app stopped it, the platform ended it or the engine
 * closed; and `stopRecording` on a recording the engine never began is
 * `Failure(InvalidState(RECORD_STOP))`, with no `Finalized` after it.
 */
class DesktopFinalizedContractTest {
    @Test
    fun everyStartedRecordingYieldsExactlyOneFinalizedInStartOrder() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        try {
            steps.open(sut)
            val events = steps.events(sut)

            steps.at(1_000)
            val stopped = steps.startRecording(sut)
            steps.frameAt(1_040)
            assertIs<CameraKResult.Success<RecordedVideo>>(steps.stopRecording(sut))

            steps.at(2_000)
            val limited = steps.startRecording(sut, steps.video(maxFileSizeBytes = 500))
            steps.frameAt(2_040)

            steps.at(3_000)
            val closed = steps.startRecording(sut)
            steps.frameAt(3_040)
            sut.close()
            rig.settle()

            val ends = events.mapNotNull { event ->
                when (event) {
                    is EngineRecordingEvent.Started -> "started ${event.path}"
                    is EngineRecordingEvent.Finalized -> {
                        val video = assertIs<CameraKResult.Success<RecordedVideo>>(event.result).value
                        "finalized ${video.path} ${event.reason}"
                    }
                    else -> null
                }
            }
            assertEquals(
                listOf(
                    "started $stopped",
                    "finalized $stopped ${RecordingEndReason.USER}",
                    "started $limited",
                    "finalized $limited ${RecordingEndReason.MAX_FILE_SIZE}",
                    "started $closed",
                    "finalized $closed ${RecordingEndReason.SHUTDOWN}",
                ),
                ends,
            )
        } finally {
            sut.close()
            rig.settle()
        }
    }

    @Test
    fun stopRecordingOnARecordingNeverBegunIsInvalidStateAndNoFinalizedFollows() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        try {
            steps.open(sut)
            val events = steps.events(sut)

            val stop = assertIs<CameraKResult.Failure>(steps.stopRecording(sut), "a stop with nothing recording")
            assertEquals(CameraOperation.RECORD_STOP, assertIs<CameraKError.InvalidState>(stop.error).operation)
            rig.settle()

            assertEquals(emptyList(), events.toList(), "events after a stop of nothing")
        } finally {
            sut.close()
            rig.settle()
        }
    }
}

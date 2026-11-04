@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.EngineRecordingEvent
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import org.bytedeco.javacv.FrameRecorder
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A recording the platform ends follows §5's partial-file rule with desktop's playable probe (§4.1):
 * at least one frame written and the recorder's `stop()` closed the container. A playable file is
 * `Success` with the reason saying why it ended (INTERRUPTED for a camera cut, ERROR for an encoder
 * error, row 9); a file with no frame is `Failure`, and the engine deletes it.
 */
class DesktopRecordingOutcomeTest {
    @Test
    fun cameraCutAfterFramesWrittenIsSuccessInterrupted() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        try {
            steps.open(sut)
            val events = steps.events(sut)
            steps.at(1_000)
            val path = steps.startRecording(sut)
            steps.frameAt(1_040)
            steps.frameAt(1_080)

            // The camera stops delivering: a failed grab() after Open.
            rig.camera.holdOpen = true
            rig.camera.failGrab(IOException("the device went away"))
            rig.settle()

            assertEquals(EngineState.Interrupted(InterruptionReason.OTHER), sut.state.value)
            val finalized = events.filterIsInstance<EngineRecordingEvent.Finalized>().single()
            assertEquals(RecordingEndReason.INTERRUPTED, finalized.reason)
            assertEquals(path, assertIs<CameraKResult.Success<RecordedVideo>>(finalized.result).value.path)
            assertTrue(rig.recorders.single().file.exists(), "the playable file of a cut recording")
        } finally {
            sut.close()
            rig.settle()
        }
    }

    @Test
    fun recorderErrorAfterFramesWrittenIsSuccessError() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        rig.recorderScript = { path ->
            ScriptedFrameRecorder(path, rig.workers, failRecord = { index -> if (index == 2) writeFailure() else null })
        }
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        try {
            steps.open(sut)
            val events = steps.events(sut)
            steps.at(1_000)
            val path = steps.startRecording(sut)
            steps.frameAt(1_040)
            steps.frameAt(1_080)
            steps.frameAt(1_120)

            val finalized = events.filterIsInstance<EngineRecordingEvent.Finalized>().single()
            assertEquals(RecordingEndReason.ERROR, finalized.reason)
            assertEquals(path, assertIs<CameraKResult.Success<RecordedVideo>>(finalized.result).value.path)
            val recorder = rig.recorders.single()
            assertEquals(2_000L, recorder.file.length(), "the two frames written before the error")
            assertEquals(1, recorder.stopCount.get(), "the container was closed")
            assertEquals(1, recorder.releaseCount.get())
            assertEquals(EngineState.Open, sut.state.value, "an encoder error stopped the camera")
        } finally {
            sut.close()
            rig.settle()
        }
    }

    @Test
    fun recorderErrorBeforeAnyFrameIsFailureAndDeleted() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        rig.recorderScript = { path ->
            ScriptedFrameRecorder(path, rig.workers, failRecord = { index -> if (index == 0) writeFailure() else null })
        }
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        try {
            steps.open(sut)
            val events = steps.events(sut)
            steps.at(1_000)
            steps.startRecording(sut)
            steps.frameAt(1_040)

            val finalized = events.filterIsInstance<EngineRecordingEvent.Finalized>().single()
            assertEquals(RecordingEndReason.ERROR, finalized.reason)
            val error = assertIs<CameraKError.Hardware>(assertIs<CameraKResult.Failure>(finalized.result).error)
            assertEquals(-22, error.platformCode, "FFmpeg's code from the recorder's message")
            assertIs<FrameRecorder.Exception>(error.cause)
            assertFalse(rig.recorders.single().file.exists(), "an unplayable file was left behind")
            assertEquals(1, rig.recorders.single().releaseCount.get())
        } finally {
            sut.close()
            rig.settle()
        }
    }

    private fun writeFailure() =
        FrameRecorder.Exception("av_interleaved_write_frame() error -22 while writing video packet")
}

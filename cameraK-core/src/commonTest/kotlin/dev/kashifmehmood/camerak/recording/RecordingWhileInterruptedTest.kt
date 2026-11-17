@file:OptIn(CameraKEngineApi::class, ExperimentalCoroutinesApi::class)

package dev.kashifmehmood.camerak.recording

import dev.kashifmehmood.camerak.capture.RecordedVideo
import dev.kashifmehmood.camerak.engine.CameraEngineFactory
import dev.kashifmehmood.camerak.error.CameraKError
import dev.kashifmehmood.camerak.error.InterruptionReason
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.state.CameraConfiguration
import dev.kashifmehmood.camerak.state.CameraKState
import dev.kashifmehmood.camerak.state.CameraKStateHolder
import dev.kashifmehmood.camerak.testing.FakeCameraEngine
import dev.kashifmehmood.camerak.testing.cameraKTestEnvironment
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Recording commands while the camera is Interrupted (design §12.4 LC'): the platform can still
 * finalize the file, so a stop reaches the engine; pause and resume stay NotReady.
 */
class RecordingWhileInterruptedTest {
    private fun TestScope.readyHolder(fake: FakeCameraEngine): CameraKStateHolder {
        val holder = CameraKStateHolder(
            CameraConfiguration(),
            CameraEngineFactory { _, _ -> fake },
            emptyList(),
            cameraKTestEnvironment(),
        )
        holder.start()
        runCurrent()
        assertIs<CameraKState.Ready>(holder.cameraState.value, "the holder did not reach Ready")
        return holder
    }

    private suspend fun CameraKStateHolder.recording(): RecordingState.Recording =
        assertIs<CameraKResult.Success<RecordingState.Recording>>(startRecording(), "the recording").value

    @Test
    fun stopWhileInterruptedFinalizesThroughTheEngine() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)
        val recording = holder.recording()
        fake.interrupt(InterruptionReason.MULTITASKING)
        runCurrent()
        assertIs<CameraKState.Interrupted>(holder.cameraState.value)

        val stopped = holder.stopRecording()

        val video = assertIs<CameraKResult.Success<RecordedVideo>>(stopped, "a stop while Interrupted").value
        assertEquals(recording.filePath, video.path)
        assertTrue("stopRecording" in fake.calls, "the stop never reached the engine: ${fake.calls}")
        val finished = assertIs<RecordingState.Finished>(holder.recordingState.value)
        assertEquals(RecordingEndReason.USER, finished.reason)
    }

    @Test
    fun pauseWhileInterruptedIsNotReady() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)
        holder.recording()
        fake.interrupt(InterruptionReason.MULTITASKING)
        runCurrent()

        assertEquals(CameraKResult.Failure(CameraKError.NotReady), holder.pauseRecording(), "pause while Interrupted")
        assertIs<RecordingState.Recording>(holder.recordingState.value)

        fake.endInterruption()
        runCurrent()
        assertEquals(CameraKResult.Success(Unit), holder.pauseRecording())
        fake.interrupt(InterruptionReason.MULTITASKING)
        runCurrent()

        assertEquals(CameraKResult.Failure(CameraKError.NotReady), holder.resumeRecording(), "resume while Interrupted")
        assertIs<RecordingState.Paused>(holder.recordingState.value)
        assertEquals(1, fake.calls.count { it == "pauseRecording" }, "engine calls: ${fake.calls}")
        assertEquals(0, fake.calls.count { it == "resumeRecording" }, "engine calls: ${fake.calls}")
    }
}

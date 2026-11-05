@file:OptIn(CameraKEngineApi::class, ExperimentalCoroutinesApi::class)

package com.kashif.cameraK.state

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.CameraEngineFactory
import com.kashif.cameraK.recording.CONFIG_CHANGE_GRACE
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.recording.RecordingState
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The config-change grace (design §7.3, §5): a host stop while a recording is live keeps the camera
 * open for CONFIG_CHANGE_GRACE, so the stop and start of an Activity recreation leave the recording
 * running; with no start inside the window the recording ends INTERRUPTED and then the camera stops.
 */
class ConfigChangeGraceTest {
    private class Rig(val fake: FakeCameraEngine, val holder: CameraKStateHolder, val events: List<CameraKEvent>)

    private fun TestScope.recordingRig(): Pair<Rig, RecordingState.Recording> {
        val fake = FakeCameraEngine()
        val holder = CameraKStateHolder(
            CameraConfiguration(),
            CameraEngineFactory { _, _ -> fake },
            emptyList(),
            cameraKTestEnvironment(),
        )
        val events = mutableListOf<CameraKEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { holder.events.collect { events += it } }
        holder.start()
        runCurrent()
        assertIs<CameraKState.Ready>(holder.cameraState.value, "the holder did not reach Ready")
        var started: CameraKResult<RecordingState.Recording>? = null
        backgroundScope.launch { started = holder.startRecording() }
        runCurrent()
        val recording = assertIs<CameraKResult.Success<RecordingState.Recording>>(started, "the recording").value
        return Rig(fake, holder, events) to recording
    }

    @Test
    fun startInsideGraceContinuesRecordingAndRegistryStaysResumed() = runTest {
        val (rig, recording) = recordingRig()

        rig.holder.stop()
        runCurrent()
        advanceTimeBy(CONFIG_CHANGE_GRACE - 1.milliseconds)
        runCurrent()
        val during = assertIs<RecordingState.Recording>(
            rig.holder.recordingState.value,
            "the host stop ended the recording before CONFIG_CHANGE_GRACE",
        )
        assertEquals(recording.recordingId, during.recordingId)
        rig.holder.start()
        runCurrent()
        advanceTimeBy(CONFIG_CHANGE_GRACE * 2)
        runCurrent()

        val live = assertIs<RecordingState.Recording>(
            rig.holder.recordingState.value,
            "a start inside the grace did not keep the recording",
        )
        assertEquals(recording.recordingId, live.recordingId)
        // On Android a stop moves the engine's registry to CREATED; no stop means it stayed RESUMED.
        assertFalse("stop" in rig.fake.calls, "the camera stopped under the recording: ${rig.fake.calls}")
        assertFalse("stopRecording" in rig.fake.calls, "the recording was stopped: ${rig.fake.calls}")
        assertIs<CameraKState.Ready>(rig.holder.cameraState.value)
        assertEquals(emptyList(), rig.events.filterIsInstance<CameraKEvent.RecordingEnded>())

        var stopped: CameraKResult<RecordedVideo>? = null
        backgroundScope.launch { stopped = rig.holder.stopRecording() }
        runCurrent()
        val video = assertIs<CameraKResult.Success<RecordedVideo>>(stopped, "the recording's own stop").value
        assertEquals(recording.filePath, video.path)
    }

    @Test
    fun noStartWithinGraceEndsRecordingInterruptedThenStops() = runTest {
        val (rig, recording) = recordingRig()

        rig.holder.stop()
        runCurrent()
        advanceTimeBy(CONFIG_CHANGE_GRACE - 1.milliseconds)
        runCurrent()
        assertIs<RecordingState.Recording>(
            rig.holder.recordingState.value,
            "the host stop ended the recording before CONFIG_CHANGE_GRACE",
        )
        assertFalse("stop" in rig.fake.calls, "the camera stopped inside the grace: ${rig.fake.calls}")
        advanceTimeBy(1.milliseconds)
        runCurrent()

        val finished = assertIs<RecordingState.Finished>(rig.holder.recordingState.value, "after the grace")
        assertEquals(recording.recordingId, finished.recordingId)
        assertEquals(RecordingEndReason.INTERRUPTED, finished.reason)
        val ended = rig.events.filterIsInstance<CameraKEvent.RecordingEnded>().single()
        assertEquals(RecordingEndReason.INTERRUPTED, ended.reason)
        assertEquals(recording.filePath, assertIs<CameraKResult.Success<RecordedVideo>>(ended.result).value.path)
        val calls = rig.fake.calls
        assertTrue(
            calls.indexOf("stopRecording") in 0 until calls.indexOf("stop"),
            "the recording did not end before the camera stopped: $calls",
        )
        assertEquals(CameraKState.Idle, rig.holder.cameraState.value)
    }

    @Test
    fun cameraStateStaysReadyWhenAStartEndsTheGrace() = runTest {
        val (rig, _) = recordingRig()
        val states = mutableListOf<CameraKState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.holder.cameraState.collect { states += it }
        }

        rig.holder.stop()
        runCurrent()
        advanceTimeBy(1.seconds)
        rig.holder.start()
        runCurrent()
        advanceTimeBy(CONFIG_CHANGE_GRACE * 2)
        runCurrent()

        assertTrue(states.all { it is CameraKState.Ready }, "the grace and the start published $states")
        assertEquals(1, rig.fake.startCalls, "the start called the open engine again: ${rig.fake.calls}")
        assertIs<CameraKState.Ready>(rig.holder.cameraState.value)
    }
}

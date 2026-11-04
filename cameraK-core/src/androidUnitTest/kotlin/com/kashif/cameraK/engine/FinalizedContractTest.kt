@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import androidx.camera.video.VideoRecordEvent.Finalize
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Finalized contract of `CameraEngine.recordingEvents` on the Android engine (w6-design-decisions;
 * the engine contract kit stays frozen until C1', so it is pinned here): every recording the engine
 * started yields exactly one Finalized, in start order, whoever ended it; a stop of a recording the
 * engine never began is `InvalidState(RECORD_STOP)` and no Finalized follows; a start the caller gave
 * up on after it reached CameraX is stopped by the next stopRecording and still yields its Finalized.
 */
@RunWith(RobolectricTestRunner::class)
class FinalizedContractTest {
    private val platform = FakeAndroidPlatform()

    @Test
    fun everyRecordingTheEngineStartedYieldsOneFinalizedInStartOrder() = runTest {
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        val events = recordingEventsOf(engine)

        val (first, firstPath) = startRecording(platform, engine)
        val stop = async { engine.stopRecording() }
        platform.settle(testScheduler)
        first.finalize()
        platform.settle(testScheduler)
        assertIs<CameraKResult.Success<RecordedVideo>>(stop.await(), "the user stop")
        val (second, secondPath) = startRecording(platform, engine)
        second.finalize(Finalize.ERROR_SOURCE_INACTIVE)
        platform.settle(testScheduler)

        val finalized = events.filterIsInstance<EngineRecordingEvent.Finalized>()
        assertEquals(2, finalized.size, "events: $events")
        assertEquals(
            listOf(firstPath, secondPath),
            finalized.map { assertIs<CameraKResult.Success<RecordedVideo>>(it.result).value.path },
        )
        assertEquals(listOf(RecordingEndReason.USER, RecordingEndReason.INTERRUPTED), finalized.map { it.reason })
        engine.closeAndJoin()
    }

    @Test
    fun stopOfARecordingTheEngineNeverBeganIsInvalidStateAndNoFinalizedFollows() = runTest {
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        val events = recordingEventsOf(engine)
        platform.backend.binding.refuseVideoCapture = true
        assertIs<CameraKResult.Failure>(
            engine.startRecording(VideoConfiguration()),
            "setup: a start that began nothing",
        )
        platform.settle(testScheduler)

        val stop = engine.stopRecording()
        platform.settle(testScheduler)

        val error = assertIs<CameraKError.InvalidState>(assertIs<CameraKResult.Failure>(stop).error)
        assertEquals(CameraOperation.RECORD_STOP, error.operation)
        assertTrue(events.isEmpty(), "events for a recording that never began: $events")
        engine.closeAndJoin()
    }

    @Test
    fun aStartTheCallerGaveUpOnIsStoppedByTheNextStopAndYieldsOneFinalized() = runTest {
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        val events = recordingEventsOf(engine)
        platform.backend.holdRecordingStart = true
        val start = async { engine.startRecording(VideoConfiguration()) }
        platform.settle(testScheduler)
        val recording = platform.backend.recordings.single()
        start.cancel()
        platform.settle(testScheduler)

        val stop = async { engine.stopRecording() }
        platform.settle(testScheduler)
        assertEquals(1, recording.stopCalls, "the stop did not reach the abandoned recording")
        recording.finalize()
        platform.settle(testScheduler)

        val stopped = stop.await()
        assertFalse(
            stopped is CameraKResult.Failure && stopped.error is CameraKError.InvalidState,
            "the stop of a recording that reached CameraX answered InvalidState: $stopped",
        )
        assertEquals(1, events.filterIsInstance<EngineRecordingEvent.Finalized>().size, "events: $events")
        engine.closeAndJoin()
    }
}

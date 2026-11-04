@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import androidx.camera.video.VideoRecordEvent.Finalize
import com.kashif.cameraK.capture.RecordedVideo
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
 * The engine observes each recording from CameraX's Start to its Finalize (§5): startRecording returns
 * once CameraX reports the start, and a recording CameraX ends by itself reaches recordingEvents with
 * no stop call (recording-terminal-events-dropped, engine flow).
 */
@RunWith(RobolectricTestRunner::class)
class RecordingEventsTest {
    private val platform = FakeAndroidPlatform()

    @Test
    fun platformEndedRecordingReachesEventsWithoutStop() = runTest {
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        val events = recordingEventsOf(engine)
        val (recording, path) = startRecording(platform, engine)

        // The host went to the background: CameraX finalizes without anyone calling stop.
        recording.finalize(Finalize.ERROR_SOURCE_INACTIVE)
        platform.settle(testScheduler)

        assertEquals(EngineRecordingEvent.Started(path, hasAudio = false), events.firstOrNull(), "events: $events")
        val finalized = assertIs<EngineRecordingEvent.Finalized>(events.getOrNull(1), "events: $events")
        assertEquals(RecordingEndReason.INTERRUPTED, finalized.reason)
        assertEquals(path, assertIs<CameraKResult.Success<RecordedVideo>>(finalized.result).value.path)
        assertEquals(2, events.size, "events: $events")
        engine.closeAndJoin()
    }

    @Test
    fun startResumesOnCameraXStart() = runTest {
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        val events = recordingEventsOf(engine)
        platform.backend.holdRecordingStart = true

        val start = async { engine.startRecording(VideoConfiguration()) }
        platform.settle(testScheduler)
        assertEquals(1, platform.backend.recordings.size, "setup: the recording reached CameraX")
        assertFalse(start.isCompleted, "startRecording returned before CameraX reported the start")
        assertTrue(events.isEmpty(), "an event before CameraX's Start: $events")

        platform.backend.recordings.single().deliverStarted()
        platform.settle(testScheduler)

        assertTrue(start.isCompleted, "startRecording did not return on CameraX's Start")
        val path = assertIs<CameraKResult.Success<String>>(start.await()).value
        assertEquals(platform.backend.recordings.single().outputFile.absolutePath, path)
        assertEquals(listOf<EngineRecordingEvent>(EngineRecordingEvent.Started(path, hasAudio = false)), events)
        platform.backend.recordings.single().finalize()
        platform.settle(testScheduler)
        engine.closeAndJoin()
    }
}

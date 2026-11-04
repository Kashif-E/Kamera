@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.controller.STOP_FINALIZE_TIMEOUT_MS
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * pauseRecording and resumeRecording return once CameraX reports the Pause or Resume, and the engine
 * emits its own Paused and Resumed then. A pause CameraX never confirms times out, bounded like a stop.
 */
@RunWith(RobolectricTestRunner::class)
class PauseResumeTest {
    private val platform = FakeAndroidPlatform()
    private val success = CameraKResult.Success(Unit)

    @Test
    fun pauseReturnsOnCameraXPauseAndEmitsPaused() = runTest {
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        val events = recordingEventsOf(engine)
        platform.backend.holdPauseResume = true
        val (recording, _) = startRecording(platform, engine)

        val pause = async { engine.pauseRecording() }
        platform.settle(testScheduler)
        assertEquals(1, recording.pauseCalls, "the pause did not reach CameraX")
        assertFalse(pause.isCompleted, "pauseRecording returned before CameraX paused")

        recording.deliverPaused()
        platform.settle(testScheduler)
        assertTrue(pause.isCompleted, "pauseRecording did not return on CameraX's Pause")
        assertEquals(success, pause.await())
        assertEquals(EngineRecordingEvent.Paused, events.last(), "events: $events")
        recording.finalize()
        platform.settle(testScheduler)
        engine.closeAndJoin()
    }

    @Test
    fun resumeReturnsOnCameraXResumeAndEmitsResumed() = runTest {
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        val events = recordingEventsOf(engine)
        val (recording, _) = startRecording(platform, engine)
        assertEquals(success, engine.pauseRecording().also { platform.settle(testScheduler) }, "setup: paused")
        platform.backend.holdPauseResume = true
        val held = platform.backend.recordings.single()

        val resume = async { engine.resumeRecording() }
        platform.settle(testScheduler)
        assertEquals(1, held.resumeCalls, "the resume did not reach CameraX")
        assertFalse(resume.isCompleted, "resumeRecording returned before CameraX resumed")

        held.deliverResumed()
        platform.settle(testScheduler)
        assertEquals(success, resume.await())
        assertEquals(EngineRecordingEvent.Resumed, events.last(), "events: $events")
        recording.finalize()
        platform.settle(testScheduler)
        engine.closeAndJoin()
    }

    @Test
    fun pauseWithoutCameraXPauseIsTimeoutRecordPause() = runTest {
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        platform.backend.holdPauseResume = true
        val (recording, _) = startRecording(platform, engine)

        val pause = async { engine.pauseRecording() }
        platform.settle(testScheduler)
        testScheduler.advanceTimeBy(STOP_FINALIZE_TIMEOUT_MS.milliseconds + 1.milliseconds)
        platform.settle(testScheduler)

        assertTrue(pause.isCompleted, "the unconfirmed pause still waits")
        val error = assertIs<CameraKError.Timeout>(assertIs<CameraKResult.Failure>(pause.await()).error)
        assertEquals(CameraOperation.RECORD_PAUSE, error.operation)
        recording.finalize()
        platform.settle(testScheduler)
        engine.closeAndJoin()
    }
}

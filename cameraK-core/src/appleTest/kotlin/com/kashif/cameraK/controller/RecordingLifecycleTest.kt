package com.kashif.cameraK.controller

import com.kashif.cameraK.video.VideoCaptureResult
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * startRecording returns once AVFoundation reports the recording started, and refuses a second
 * start; stopRecording reports the result of a recording that ended by itself and gives up after a
 * bounded wait; cleanup stops an active recording; pause is refused where the movie output cannot
 * pause and is reported only when the output confirms it.
 */
class RecordingLifecycleTest {
    private val rig = CaptureRig()
    private val recorder = FakeMovieRecorder()
    private val scope = MainScope()
    private val config = VideoConfiguration { enableAudio = false }

    init {
        rig.controller.movieRecorder = recorder
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        rig.tearDown()
    }

    /** A suspend call running on main; [outcome] is set when it returns or throws. */
    private inner class Call<T>(block: suspend () -> T) {
        var outcome: Result<T>? = null
        val job = scope.launch {
            outcome = try {
                Result.success(block())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        fun await(what: String, seconds: Double = 10.0): Result<T> {
            awaitTrue(what, seconds) { outcome != null }
            return outcome!!
        }
    }

    private fun startedRecording(): FakeMovieRecorder.Start {
        val before = recorder.starts.size
        val start = Call { rig.controller.startRecording(config) }
        awaitTrue("the recording to start on the movie output") { recorder.starts.size > before }
        val started = recorder.starts.last()
        recorder.reportStarted(started)
        start.await("startRecording to return").getOrThrow()
        return started
    }

    @Test
    fun startRecordingReturnsOnlyOnceTheRecordingHasStarted() {
        rig.start()
        val start = Call { rig.controller.startRecording(config) }
        awaitTrue("the recording to start on the movie output") { recorder.starts.isNotEmpty() }
        drainSessionThenMain(rig.custom)
        assertNull(start.outcome, "startRecording returned before AVFoundation reported the recording started")

        val started = recorder.starts.single()
        recorder.reportStarted(started)
        assertEquals(started.url.path, start.await("startRecording to return").getOrThrow())
    }

    @Test
    fun aSecondStartWhileRecordingFails() {
        rig.start()
        startedRecording()

        val second = Call { rig.controller.startRecording(config) }.await("the second startRecording to return")

        assertTrue(second.isFailure, "a second start while recording returned ${second.getOrNull()}")
        assertEquals(1, recorder.starts.size, "a second start reached the movie output")
    }

    @Test
    fun aRecordingThatEndedByItselfStillReportsItsResult() {
        rig.start()
        val started = startedRecording()

        recorder.reportFinished(started, error = null) // ended without stopRecording (a limit, an interruption)
        drainSessionThenMain(rig.custom)
        val result = Call { rig.controller.stopRecording() }.await("stopRecording to return").getOrThrow()

        val success = assertIs<VideoCaptureResult.Success>(result, "the self-terminated recording's result was lost")
        assertEquals(started.url.path, success.filePath)
    }

    @Test
    fun stopRecordingGivesUpWhenTheRecordingNeverFinishes() {
        rig.controller.recordingFinishTimeoutMs = 200
        rig.start()
        startedRecording()

        val result = Call {
            rig.controller.stopRecording()
        }.await("stopRecording to give up", seconds = 5.0).getOrThrow()

        assertIs<VideoCaptureResult.Error>(result, "stopRecording reported $result for a recording that never finished")
        assertEquals(1, recorder.stops, "stopRecording did not ask the movie output to stop")
    }

    @Test
    fun cleanupStopsAnActiveRecording() {
        rig.start()
        startedRecording()

        rig.controller.cleanup()
        drainSessionThenMain(rig.custom)

        assertEquals(1, recorder.stops, "cleanup left the recording running")
    }

    @Test
    fun pauseIsRefusedWhereTheMovieOutputCannotPause() {
        rig.controller.pauseSupported = { false }
        rig.start()
        startedRecording()

        val pause = Call { rig.controller.pauseRecording() }.await("pauseRecording to return")

        assertIs<UnsupportedOperationException>(pause.exceptionOrNull(), "pause below iOS 18 was not refused: $pause")
        assertEquals(0, recorder.pauses, "pause reached a movie output that cannot pause")
    }

    @Test
    fun pauseIsReportedOnlyWhenTheMovieOutputConfirmsIt() {
        rig.controller.pauseSupported = { true }
        recorder.pauseTakesEffect = false
        rig.start()
        startedRecording()

        val pause = Call { rig.controller.pauseRecording() }.await("pauseRecording to return")

        assertTrue(pause.isFailure, "pauseRecording reported a pause the movie output did not apply")
    }

    @Test
    fun pauseAndResumeWithoutARecordingDoNothing() {
        rig.start()

        val pause = Call { rig.controller.pauseRecording() }.await("pauseRecording to return")
        val resume = Call { rig.controller.resumeRecording() }.await("resumeRecording to return")

        assertTrue(pause.isSuccess, "pause without a recording was not a no-op: $pause")
        assertTrue(resume.isSuccess, "resume without a recording was not a no-op: $resume")
        assertEquals(0, recorder.pauses)
    }

    @Test
    fun resumeIsANoOpWhereTheMovieOutputCannotPause() {
        rig.controller.pauseSupported = { false }
        rig.start()
        startedRecording()

        val resume = Call { rig.controller.resumeRecording() }.await("resumeRecording to return")

        assertTrue(resume.isSuccess, "resuming a recording that cannot have been paused was not a no-op: $resume")
    }
}

package com.kashif.cameraK.controller

import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * startRecording returns once AVFoundation reports the recording started, and refuses a second
 * start; stopRecording gives up after a bounded wait; close stops an active recording (§2.3 rule 3);
 * pause and resume without a recording are InvalidState. (PauseResumeTest covers pause support.)
 */
class RecordingLifecycleTest {
    private val rig = CaptureRig()
    private val recorder = FakeMovieRecorder()
    private val scope = MainScope()
    private val config = VideoConfiguration { enableAudio = false }

    init {
        rig.engine.movieRecorder = recorder
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
        val start = Call { rig.engine.startRecording(config) }
        awaitTrue("the recording to start on the movie output") { recorder.starts.size > before }
        val started = recorder.starts.last()
        recorder.reportStarted(started)
        assertIs<CameraKResult.Success<String>>(start.await("startRecording to return").getOrThrow())
        return started
    }

    @Test
    fun startRecordingReturnsOnlyOnceTheRecordingHasStarted() {
        rig.start()
        val start = Call { rig.engine.startRecording(config) }
        awaitTrue("the recording to start on the movie output") { recorder.starts.isNotEmpty() }
        drainSessionThenMain(rig.custom)
        assertNull(start.outcome, "startRecording returned before AVFoundation reported the recording started")

        val started = recorder.starts.single()
        recorder.reportStarted(started)
        assertEquals(CameraKResult.Success(started.url.path!!), start.await("startRecording to return").getOrThrow())
    }

    @Test
    fun aSecondStartWhileRecordingFails() {
        rig.start()
        startedRecording()

        val second = Call { rig.engine.startRecording(config) }.await("the second startRecording to return")

        assertIs<CameraKResult.Failure>(second.getOrThrow(), "a second start while recording returned $second")
        assertEquals(1, recorder.starts.size, "a second start reached the movie output")
    }

    @Test
    fun stopRecordingGivesUpWhenTheRecordingNeverFinishes() {
        rig.engine.recorder.finishTimeoutMs = 200
        rig.start()
        startedRecording()

        val result = Call {
            rig.engine.stopRecording()
        }.await("stopRecording to give up", seconds = 5.0).getOrThrow()

        assertIs<CameraKResult.Failure>(result, "stopRecording reported $result for a recording that never finished")
        assertEquals(1, recorder.stops, "stopRecording did not ask the movie output to stop")
    }

    @Test
    fun closeStopsAnActiveRecording() {
        rig.start()
        startedRecording()

        rig.engine.close()
        drainSessionThenMain(rig.custom)

        assertEquals(1, recorder.stops, "close left the recording running")
    }

    @Test
    fun pauseAndResumeWithoutARecordingAreInvalidState() {
        rig.start()

        val pause = Call { rig.engine.pauseRecording() }.await("pauseRecording to return").getOrThrow()
        val resume = Call { rig.engine.resumeRecording() }.await("resumeRecording to return").getOrThrow()

        val pauseError = assertIs<CameraKError.InvalidState>(assertIs<CameraKResult.Failure>(pause).error)
        assertEquals(CameraOperation.RECORD_PAUSE, pauseError.operation, "pause without a recording")
        val resumeError = assertIs<CameraKError.InvalidState>(assertIs<CameraKResult.Failure>(resume).error)
        assertEquals(CameraOperation.RECORD_RESUME, resumeError.operation, "resume without a recording")
        assertEquals(0, recorder.pauses)
    }
}

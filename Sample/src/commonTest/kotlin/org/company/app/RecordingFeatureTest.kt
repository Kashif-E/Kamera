@file:OptIn(ExperimentalCoroutinesApi::class, CameraKEngineApi::class)

package org.company.app

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.error.StorageFailure
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.recording.RecordingState
import com.kashif.cameraK.recording.elapsed
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The Sample's recording on the holder: the platform ends a recording at its limit or with an error,
 * and the screen must stop showing it and say how it ended, without a tap.
 */
class RecordingFeatureTest {
    private class FakeRecorder(private val scope: TestScope) : SampleRecorder {
        val state = MutableStateFlow<RecordingState>(RecordingState.Idle)
        override val recordingState: StateFlow<RecordingState> = state
        val configurations = mutableListOf<VideoConfiguration>()
        var stops = 0
        var startResult: CameraKResult.Failure? = null
        private var lastId = 0L

        override suspend fun startRecording(
            configuration: VideoConfiguration,
        ): CameraKResult<RecordingState.Recording> {
            configurations += configuration
            startResult?.let { return it }
            val recording = RecordingState.Recording(
                recordingId = ++lastId,
                filePath = "/fake/VID_$lastId.mp4",
                activeSince = scope.testScheduler.timeSource.markNow(),
                accumulated = Duration.ZERO,
                hasAudio = configuration.enableAudio,
            )
            state.value = recording
            return CameraKResult.Success(recording)
        }

        override suspend fun stopRecording(): CameraKResult<RecordedVideo> {
            stops++
            val result = CameraKResult.Success(video(lastId))
            platformEnds(result, RecordingEndReason.USER)
            return result
        }

        fun platformEnds(result: CameraKResult<RecordedVideo>, reason: RecordingEndReason) {
            state.value = RecordingState.Finished(lastId, result, reason)
        }

        fun video(id: Long) = RecordedVideo(
            path = "/fake/VID_$id.mp4",
            duration = 1.seconds,
            width = 1920,
            height = 1080,
            bitrateBps = null,
            hasAudio = true,
        )
    }

    private fun TestScope.started(recorder: FakeRecorder): Pair<RecordingFeature, List<String>> {
        val recording = RecordingFeature(recorder)
        val notices = mutableListOf<String>()
        backgroundScope.launch { recording.reportEnds() }
        backgroundScope.launch { recording.notices.collect { notices += it } }
        runCurrent()
        return recording to notices
    }

    @Test
    fun theFiveMinuteLimitIsAskedOfThePlatformAndItsEndIsShown() = runTest {
        val recorder = FakeRecorder(this)
        val (recording, notices) = started(recorder)

        recording.toggleRecording()
        assertEquals(5.minutes, recorder.configurations.single().maxDuration)
        assertTrue(recording.state.value.isLive(), "the recording did not start")
        advanceTimeBy(5.minutes)
        assertEquals(5.minutes.inWholeMilliseconds, recording.state.value.elapsed().inWholeMilliseconds)

        recorder.platformEnds(CameraKResult.Success(recorder.video(1)), RecordingEndReason.MAX_DURATION)
        runCurrent()
        assertFalse(recording.state.value.isLive(), "the Sample still shows a recording the limit ended")
        assertEquals(0L, recording.state.value.elapsed().inWholeMilliseconds)
        assertEquals(listOf("Recording reached its time limit and was saved"), notices)
        assertEquals(0, recorder.stops, "the Sample stopped a recording the platform had already ended")
    }

    @Test
    fun aRecordingThePlatformEndsWithAnErrorReachesTheScreen() = runTest {
        val recorder = FakeRecorder(this)
        val (recording, notices) = started(recorder)

        recording.toggleRecording()
        recorder.platformEnds(
            CameraKResult.Failure(CameraKError.Storage(StorageFailure.FULL, keptFilePath = null)),
            RecordingEndReason.ERROR,
        )
        runCurrent()

        assertFalse(recording.state.value.isLive())
        assertEquals(listOf("Recording failed: could not write to storage"), notices)

        // The next tap starts a new recording rather than stopping the ended one.
        recording.toggleRecording()
        assertEquals(0, recorder.stops)
        assertEquals(2, recorder.configurations.size)
    }

    @Test
    fun aSecondTapStopsTheRecordingOnTheHolderAndReportsItOnce() = runTest {
        val recorder = FakeRecorder(this)
        val (recording, notices) = started(recorder)

        recording.toggleRecording()
        recording.toggleRecording()
        runCurrent()

        assertEquals(1, recorder.stops)
        assertFalse(recording.state.value.isLive())
        assertEquals(listOf("Recording saved"), notices)
    }

    @Test
    fun aStartTheHolderRefusesIsReportedAndNothingRecords() = runTest {
        val recorder = FakeRecorder(this)
        recorder.startResult = CameraKResult.Failure(CameraKError.Busy(CameraOperation.RECORD_START))
        val (recording, notices) = started(recorder)

        recording.toggleRecording()
        runCurrent()

        assertFalse(recording.state.value.isLive())
        assertEquals(listOf("Recording did not start: a recording is already under way"), notices)
    }

    // What `rememberRecordingElapsed(holder)` holds: the recording state's elapsed time at the last tick.
    @Test
    fun theTopBarShowsTheHoldersElapsedTimeWhileRecordingAndZeroOtherwise() = runTest {
        val recorder = FakeRecorder(this)
        val (recording, _) = started(recorder)
        assertEquals(0L, recording.state.value.shownDurationMs(Duration.ZERO), "nothing is recording")

        recording.toggleRecording()
        advanceTimeBy(3.seconds)
        val lastTick = recording.state.value.elapsed()
        assertEquals(3.seconds.inWholeMilliseconds, recording.state.value.shownDurationMs(lastTick))

        val paused = RecordingState.Paused(recordingId = 1, filePath = "/fake/VID_1.mp4", accumulated = 3.seconds)
        assertEquals(3.seconds.inWholeMilliseconds, paused.shownDurationMs(paused.elapsed()), "a pause hid the time")

        recorder.platformEnds(CameraKResult.Success(recorder.video(1)), RecordingEndReason.MAX_DURATION)
        runCurrent()
        assertEquals(
            0L,
            recording.state.value.shownDurationMs(lastTick),
            "a finished recording's last tick stayed on screen",
        )
        assertEquals(0L, RecordingState.Idle.shownDurationMs(lastTick))
    }

    // The Sample logs through CameraKLogger, as an app would, and a log line never names the file.
    @Test
    fun aRecordingsStartAndSaveAreLoggedWithoutItsPath() = runTest {
        val logged = mutableListOf<Triple<CameraKLogger.Level, String, String>>()
        val wasEnabled = CameraKLogger.enabled
        val oldSink = CameraKLogger.sink
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, tag, message, _ -> logged += Triple(level, tag, message) }
        try {
            val recorder = FakeRecorder(this)
            val (recording, _) = started(recorder)

            recording.toggleRecording()
            recording.toggleRecording()
            runCurrent()
        } finally {
            CameraKLogger.enabled = wasEnabled
            CameraKLogger.sink = oldSink
        }

        assertEquals(
            listOf(
                Triple(CameraKLogger.Level.DEBUG, "Sample", "Recording started"),
                Triple(CameraKLogger.Level.DEBUG, "Sample", "Recording saved"),
            ),
            logged,
        )
        assertTrue(logged.none { "/fake/" in it.third }, "a log line names the recording's file: $logged")
    }

    @Test
    fun anEndFromBeforeTheScreenIsNotReportedAgain() = runTest {
        val recorder = FakeRecorder(this)
        recorder.state.value =
            RecordingState.Finished(7, CameraKResult.Success(recorder.video(7)), RecordingEndReason.USER)
        val (_, notices) = started(recorder)

        assertEquals(emptyList(), notices)
    }
}

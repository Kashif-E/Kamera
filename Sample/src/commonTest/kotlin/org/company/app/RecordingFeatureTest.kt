@file:OptIn(ExperimentalCoroutinesApi::class, CameraKEngineApi::class)

package org.company.app

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.error.StorageFailure
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.recording.RecordingState
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
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
        assertEquals(5.minutes.inWholeMilliseconds, recording.durationMs)

        recorder.platformEnds(CameraKResult.Success(recorder.video(1)), RecordingEndReason.MAX_DURATION)
        runCurrent()
        assertFalse(recording.state.value.isLive(), "the Sample still shows a recording the limit ended")
        assertEquals(0L, recording.durationMs)
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

    @Test
    fun anEndFromBeforeTheScreenIsNotReportedAgain() = runTest {
        val recorder = FakeRecorder(this)
        recorder.state.value =
            RecordingState.Finished(7, CameraKResult.Success(recorder.video(7)), RecordingEndReason.USER)
        val (_, notices) = started(recorder)

        assertEquals(emptyList(), notices)
    }
}

@file:OptIn(ExperimentalCoroutinesApi::class)

package org.company.app

import com.kashif.cameraK.video.VideoCaptureResult
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The Sample's recording on the 1.x controller, which reports no end of its own: the platform stops
 * at the five-minute limit, so the Sample stops there too and collects the platform's result, or its
 * screen would keep showing a recording that has ended.
 */
class RecordingFeatureTest {
    private class FakeRecorder : SampleRecorder {
        var starts = 0
        var stops = 0

        override suspend fun startRecording(configuration: VideoConfiguration): String {
            starts++
            return "/fake/VID_$starts.mp4"
        }

        override suspend fun stopRecording(): VideoCaptureResult {
            stops++
            return VideoCaptureResult.Success(filePath = "/fake/VID_$starts.mp4", durationMs = 0L)
        }
    }

    private fun TestScope.feature() = RecordingFeature(backgroundScope, testScheduler.timeSource)

    @Test
    fun theFiveMinuteLimitEndsTheRecording() = runTest {
        val recorder = FakeRecorder()
        val recording = feature()
        assertEquals(5.minutes, recording.configuration.maxDuration)

        recording.toggleRecording(recorder)
        assertTrue(recording.isRecording.value, "the recording did not start")
        advanceTimeBy(5.minutes - 1.milliseconds)
        runCurrent()
        assertEquals(0, recorder.stops, "stopped before the limit")
        assertTrue(recording.isRecording.value)

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(1, recorder.stops, "the five-minute limit did not stop the recording")
        assertFalse(recording.isRecording.value, "the Sample still shows a recording the limit ended")
        assertEquals(0L, recording.durationMs)
    }

    @Test
    fun eachRecordingHasItsOwnLimit() = runTest {
        val recorder = FakeRecorder()
        val recording = feature()

        recording.toggleRecording(recorder)
        advanceTimeBy(1.minutes)
        recording.toggleRecording(recorder)
        assertEquals(1, recorder.stops)
        recording.toggleRecording(recorder)

        // Five minutes after the first start: the stopped recording's limit must not end the second.
        advanceTimeBy(4.minutes)
        runCurrent()
        assertEquals(1, recorder.stops, "an earlier recording's limit stopped the second one")
        assertTrue(recording.isRecording.value)

        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(2, recorder.stops, "the second recording's limit did not stop it")
        assertFalse(recording.isRecording.value)
    }
}

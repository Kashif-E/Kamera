package com.kashif.cameraK.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class RecordingElapsedTest {
    @Test
    fun elapsedAcrossPauseUnderTestTimeSource() {
        val time = TestTimeSource()
        val recording = RecordingState.Recording(
            recordingId = 1,
            filePath = "/app/VID_1.mp4",
            activeSince = time.markNow(),
            accumulated = Duration.ZERO,
            hasAudio = false,
        )
        time += 3.seconds
        assertEquals(3.seconds, recording.elapsed(), "elapsed while recording")

        val paused = RecordingState.Paused(1, "/app/VID_1.mp4", accumulated = recording.elapsed())
        time += 10.seconds
        assertEquals(3.seconds, paused.elapsed(), "a pause freezes elapsed")

        val resumed = RecordingState.Recording(1, "/app/VID_1.mp4", time.markNow(), paused.accumulated, false)
        time += 2.seconds
        assertEquals(5.seconds, resumed.elapsed(), "resume adds to the time before the pause")
    }

    @Test
    fun elapsedIsZeroOutsideARecording() {
        assertEquals(Duration.ZERO, RecordingState.Idle.elapsed())
        assertEquals(Duration.ZERO, RecordingState.Stopping(1, "/app/VID_1.mp4", RecordingEndReason.USER).elapsed())
    }
}

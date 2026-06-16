package com.kashif.cameraK.state

import com.kashif.cameraK.video.VideoCaptureResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CameraStateVideoTest {

    // ═══════════════════════════════════════════════════════════════
    // CameraUIState Video Fields Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun cameraUIState_defaultVideoFieldsAreInactive() {
        val state = CameraUIState()
        assertFalse(state.isRecording)
        assertFalse(state.isPaused)
        assertEquals(0L, state.recordingDurationMs)
    }

    @Test
    fun cameraUIState_copyUpdatesRecordingFields() {
        val state = CameraUIState()
        val recording = state.copy(isRecording = true, recordingDurationMs = 5000L)
        assertTrue(recording.isRecording)
        assertFalse(recording.isPaused)
        assertEquals(5000L, recording.recordingDurationMs)
    }

    @Test
    fun cameraUIState_pausedState() {
        val state = CameraUIState(isRecording = true, isPaused = true, recordingDurationMs = 3000L)
        assertTrue(state.isRecording)
        assertTrue(state.isPaused)
        assertEquals(3000L, state.recordingDurationMs)
    }

    @Test
    fun cameraUIState_resetRecordingFields() {
        val recording = CameraUIState(isRecording = true, isPaused = false, recordingDurationMs = 10_000L)
        val reset = recording.copy(isRecording = false, isPaused = false, recordingDurationMs = 0L)
        assertFalse(reset.isRecording)
        assertFalse(reset.isPaused)
        assertEquals(0L, reset.recordingDurationMs)
    }

    @Test
    fun cameraUIState_videoFieldsDontAffectOtherFields() {
        val state = CameraUIState(
            zoomLevel = 2.5f,
            isRecording = true,
            recordingDurationMs = 5000L,
        )
        assertEquals(2.5f, state.zoomLevel)
        assertTrue(state.isRecording)
    }

    // ═══════════════════════════════════════════════════════════════
    // CameraKEvent Video Event Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun recordingStartedEvent_holdsFilePath() {
        val event = CameraKEvent.RecordingStarted("/tmp/video.mp4")
        assertIs<CameraKEvent.RecordingStarted>(event)
        assertEquals("/tmp/video.mp4", event.filePath)
    }

    @Test
    fun recordingStoppedEvent_holdsResult() {
        val result = VideoCaptureResult.Success("/tmp/video.mp4", 5000L)
        val event = CameraKEvent.RecordingStopped(result)
        assertIs<CameraKEvent.RecordingStopped>(event)
        assertIs<VideoCaptureResult.Success>(event.result)
        assertEquals("/tmp/video.mp4", (event.result as VideoCaptureResult.Success).filePath)
    }

    @Test
    fun recordingFailedEvent_holdsException() {
        val exception = RuntimeException("test error")
        val event = CameraKEvent.RecordingFailed(exception)
        assertIs<CameraKEvent.RecordingFailed>(event)
        assertEquals("test error", event.exception.message)
    }

    @Test
    fun recordingMaxDurationReachedEvent_holdsPathAndDuration() {
        val event = CameraKEvent.RecordingMaxDurationReached("/tmp/video.mp4", 60_000L)
        assertIs<CameraKEvent.RecordingMaxDurationReached>(event)
        assertEquals("/tmp/video.mp4", event.filePath)
        assertEquals(60_000L, event.durationMs)
    }

    @Test
    fun videoEventsAreDistinctFromImageEvents() {
        val recordingStarted: CameraKEvent = CameraKEvent.RecordingStarted("/path")
        val captureFailed: CameraKEvent = CameraKEvent.CaptureFailed(Exception("test"))
        val recordingFailed: CameraKEvent = CameraKEvent.RecordingFailed(Exception("test"))

        assertIs<CameraKEvent.RecordingStarted>(recordingStarted)
        assertIs<CameraKEvent.CaptureFailed>(captureFailed)
        assertIs<CameraKEvent.RecordingFailed>(recordingFailed)

        // They are different event types
        assertFalse(recordingFailed is CameraKEvent.CaptureFailed)
        assertFalse(captureFailed is CameraKEvent.RecordingFailed)
    }

    @Test
    fun recordingStartedEquality() {
        val a = CameraKEvent.RecordingStarted("/path")
        val b = CameraKEvent.RecordingStarted("/path")
        assertEquals(a, b)
    }

    @Test
    fun recordingStoppedEquality() {
        val result = VideoCaptureResult.Success("/path", 1000L)
        val a = CameraKEvent.RecordingStopped(result)
        val b = CameraKEvent.RecordingStopped(result)
        assertEquals(a, b)
    }

    // ═══════════════════════════════════════════════════════════════
    // CameraConfiguration Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun cameraConfiguration_mirrorFrontCameraDefaultsToFalse() {
        assertFalse(CameraConfiguration().mirrorFrontCamera)
    }

    @Test
    fun cameraConfiguration_mirrorFrontCameraPropagates() {
        assertTrue(CameraConfiguration(mirrorFrontCamera = true).mirrorFrontCamera)
        assertTrue(CameraConfiguration().copy(mirrorFrontCamera = true).mirrorFrontCamera)
    }
}

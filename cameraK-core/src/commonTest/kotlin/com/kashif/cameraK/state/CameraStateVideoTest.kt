@file:OptIn(CameraKEngineApi::class, ExperimentalCoroutinesApi::class)

package com.kashif.cameraK.state

import com.kashif.cameraK.engine.CameraEngineFactory
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoCaptureResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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

    @Test
    fun uiStateRecordingFieldsFollowTheRecordingController() = runTest {
        val fake = FakeCameraEngine()
        val holder = CameraKStateHolder(
            CameraConfiguration(),
            CameraEngineFactory { _, _ -> fake },
            emptyList(),
            cameraKTestEnvironment(),
        )
        holder.start()
        runCurrent()
        assertIs<CameraKState.Ready>(holder.cameraState.value)

        fun assertFields(recording: Boolean, paused: Boolean, durationMs: Long, moment: String) {
            val state = holder.uiState.value
            assertEquals(recording, state.isRecording, "isRecording $moment")
            assertEquals(paused, state.isPaused, "isPaused $moment")
            assertEquals(durationMs, state.recordingDurationMs, "recordingDurationMs $moment")
        }

        assertFields(recording = false, paused = false, durationMs = 0L, moment = "before a recording")
        assertIs<CameraKResult.Success<*>>(holder.startRecording())
        assertFields(recording = true, paused = false, durationMs = 0L, moment = "at the start")
        advanceTimeBy(1.seconds)
        runCurrent()
        assertFields(recording = true, paused = false, durationMs = 1_000L, moment = "a second in")

        assertEquals(CameraKResult.Success(Unit), holder.pauseRecording())
        assertFields(recording = true, paused = true, durationMs = 1_000L, moment = "at the pause")
        advanceTimeBy(3.seconds)
        runCurrent()
        assertFields(recording = true, paused = true, durationMs = 1_000L, moment = "while paused")

        assertEquals(CameraKResult.Success(Unit), holder.resumeRecording())
        advanceTimeBy(500.milliseconds)
        runCurrent()
        assertFields(recording = true, paused = false, durationMs = 1_500L, moment = "after the resume")

        assertIs<CameraKResult.Success<*>>(holder.stopRecording())
        assertFields(recording = false, paused = false, durationMs = 0L, moment = "after the stop")
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
        assertTrue(CameraConfiguration { mirrorFrontCamera = true }.mirrorFrontCamera)
        assertTrue(CameraConfiguration().copy { mirrorFrontCamera = true }.mirrorFrontCamera)
    }
}

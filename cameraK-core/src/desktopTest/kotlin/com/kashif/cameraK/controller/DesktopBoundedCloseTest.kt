@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.EngineRecordingEvent
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.recording.SHUTDOWN_STOP_TIMEOUT
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A direct `close()` waits at most [SHUTDOWN_STOP_TIMEOUT] for a recorder's `stop()` (w7 decisions,
 * D3' carry-over 2). When the recorder never returns, the engine logs, emits the recording's one
 * `Finalized` with `Timeout(RECORD_STOP)` and reason ERROR, leaves the recorder's thread to end by
 * itself, and releases the camera. A `stop()` that returns later yields no second `Finalized`.
 */
class DesktopBoundedCloseTest {
    private val errors = CopyOnWriteArrayList<String>()

    @AfterTest
    fun tearDown() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun closeGivesUpOnARecorderStopThatNeverReturnsAfterTheShutdownTimeout() = runTest {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, _ -> if (level == CameraKLogger.Level.ERROR) errors += message }
        val rig = DesktopEngineRig(testScheduler)
        rig.recorderScript = { path -> ScriptedFrameRecorder(path, rig.workers).apply { holdStop = true } }
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        try {
            steps.open(sut)
            val events = steps.events(sut)
            steps.at(1_000)
            steps.startRecording(sut)
            steps.frameAt(1_040)

            sut.close()
            rig.settle()
            advanceTimeBy(SHUTDOWN_STOP_TIMEOUT - 1.milliseconds)
            rig.settle()
            assertEquals(1, rig.recorders.single().stopCount.get(), "the recorder's stop was not called")
            assertTrue(events.none { it is EngineRecordingEvent.Finalized }, "finalized before the timeout")
            assertTrue(rig.camera.isOpen, "the camera was released before the recording ended")

            advanceTimeBy(2.milliseconds)
            rig.settle()

            val finalized = events.filterIsInstance<EngineRecordingEvent.Finalized>()
            assertEquals(1, finalized.size, "Finalized events after the timeout: $events")
            val timeout =
                assertIs<CameraKError.Timeout>(assertIs<CameraKResult.Failure>(finalized.single().result).error)
            assertEquals(CameraOperation.RECORD_STOP, timeout.operation)
            assertEquals(RecordingEndReason.ERROR, finalized.single().reason)
            assertFalse(rig.camera.isOpen, "the camera was not released after the timeout")
            assertEquals(1, rig.camera.releaseCount, "camera releases")
            assertTrue(errors.any { "stop" in it }, "the timeout was not logged: $errors")

            // The recorder's stop returns at last: its thread ends, and nothing more is emitted.
            rig.recorders.single().finishStop()
            rig.settle()
            assertEquals(1, events.count { it is EngineRecordingEvent.Finalized }, "a second Finalized: $events")
        } finally {
            rig.recorders.forEach { it.finishStop() }
            rig.settle()
        }
    }
}

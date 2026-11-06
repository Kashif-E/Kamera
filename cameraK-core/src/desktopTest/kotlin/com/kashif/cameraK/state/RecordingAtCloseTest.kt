@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.state

import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.recording.RecordingState
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A recording start still waiting on the engine when the holder closes ends with `Failure(Closed)`.
 * That end is not the start failing (CC-18): nothing is logged as a failure and no recording state
 * is left behind. It replaces the 1.x `startRecordingInFlightAtCloseReportsNoFailure`.
 */
class RecordingAtCloseTest {
    private val errors = CopyOnWriteArrayList<Throwable?>()

    @AfterTest
    fun tearDown() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    private fun captureErrorLogs() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, _, throwable -> if (level == CameraKLogger.Level.ERROR) errors += throwable }
    }

    @Test
    fun startInFlightAtCloseReportsNoFailure() = runTest {
        captureErrorLogs()
        val fake = FakeCameraEngine().apply { autoStartRecording = false }
        val holder = CameraKStateHolder(CameraConfiguration(), { _, _ -> fake }, emptyList(), cameraKTestEnvironment())
        holder.start()
        runCurrent()
        assertIs<CameraKState.Ready>(holder.cameraState.value)

        val start = async { holder.startRecording() }
        runCurrent()
        assertIs<RecordingState.Starting>(holder.recordingState.value, "the start is not in flight")

        holder.closeAndJoin()

        assertEquals(CameraKResult.Failure(CameraKError.Closed), start.await())
        assertEquals(RecordingState.Idle, holder.recordingState.value, "a recording state was left after close")
        assertEquals(
            emptyList(),
            errors.filterIsInstance<CancellationException>(),
            "the cancelled start was logged as a failure",
        )
    }
}

@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.state

import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.recording.RECORDING_START_TIMEOUT
import com.kashif.cameraK.recording.RecordingState
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A recording start the platform never confirms is stopped, in case the platform began it. What
 * that stop returned decides whether a finalization is still owed, so it is logged, by its kind and
 * never with the file's path.
 */
class UnconfirmedStartLogTest {
    private val messages = CopyOnWriteArrayList<String>()

    @AfterTest
    fun tearDown() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun theStopOfAnUnconfirmedStartLogsWhatItReturned() = runTest {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { _, _, message, _ -> messages += message }
        val fake = FakeCameraEngine().apply { autoStartRecording = false }
        val holder = CameraKStateHolder(CameraConfiguration(), { _, _ -> fake }, emptyList(), cameraKTestEnvironment())
        holder.start()
        runCurrent()
        assertIs<CameraKState.Ready>(holder.cameraState.value)

        val start = async { holder.startRecording() }
        runCurrent()
        advanceTimeBy(RECORDING_START_TIMEOUT)
        runCurrent()

        assertIs<CameraKError.Timeout>(assertIs<CameraKResult.Failure>(start.await()).error)
        assertEquals(RecordingState.Idle, holder.recordingState.value)
        // The fake has nothing to stop for a start it never confirmed: InvalidState.
        assertTrue(
            messages.any { "stop" in it && "InvalidState" in it },
            "no log of what the unconfirmed start's stop returned: $messages",
        )
        assertTrue(messages.none { "/fake" in it }, "a log named the file: $messages")
        holder.closeAndJoin()
    }
}

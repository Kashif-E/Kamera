@file:OptIn(CameraKEngineApi::class, ExperimentalCoroutinesApi::class)

package com.kashif.cameraK.compose

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import com.kashif.cameraK.recording.RecordingState
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * rememberRecordingElapsed recomposes its reader once per tick, not once per frame (design §5, bar
 * §7): a recording timer drawn at 60 frames a second still reads a new value only when it changes.
 */
class RecordingElapsedTest {
    @Test
    fun ticksAtTheRequestedRateNotPerFrame() = runTest {
        val fake = FakeCameraEngine()
        val holder = CameraKStateHolder(CameraConfiguration(), { _, _ -> fake }, emptyList(), cameraKTestEnvironment())
        holder.start()
        runCurrent()
        assertIs<CameraKState.Ready>(holder.cameraState.value)
        assertIs<CameraKResult.Success<RecordingState.Recording>>(holder.startRecording())

        val read = mutableListOf<Duration>()
        val scene = ImageComposeScene(
            width = 10,
            height = 10,
            coroutineContext = StandardTestDispatcher(testScheduler),
        ) {
            val elapsed by rememberRecordingElapsed(holder, tick = 1.seconds)
            SideEffect { read += elapsed }
        }
        try {
            // A frame every 16 ms for three and a half seconds of recording.
            var frameTime = Duration.ZERO
            while (frameTime < 3500.milliseconds) {
                Snapshot.sendApplyNotifications()
                scene.render(frameTime.inWholeNanoseconds)
                runCurrent()
                advanceTimeBy(FRAME)
                frameTime += FRAME
            }
        } finally {
            scene.close()
        }

        assertEquals(listOf(0.seconds, 1.seconds, 2.seconds, 3.seconds), read, "the reader recomposed at $read")
    }

    private companion object {
        val FRAME = 16.milliseconds
    }
}

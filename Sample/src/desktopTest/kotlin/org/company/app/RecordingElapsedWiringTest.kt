@file:OptIn(ExperimentalCoroutinesApi::class)

package org.company.app

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import com.kashif.cameraK.recording.RecordingState
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.fakeCameraKStateHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * w9 carry-over 4 (W7b LC'): the camera screen's top bar shows the holder's recorded time, from
 * `rememberRecordingElapsed(holder)`, ticking once a second while recording and holding while paused.
 * CameraScreen reads it through [rememberShownRecordingDurationMs].
 */
class RecordingElapsedWiringTest {
    @Test
    fun theShownDurationTicksWithTheHoldersRecording() = runTest {
        val fake = FakeCameraEngine().apply { supportsPause = true }
        val holder = fakeCameraKStateHolder(fake = fake)
        holder.start()
        runCurrent()
        assertIs<CameraKState.Ready>(holder.cameraState.value)
        assertIs<CameraKResult.Success<RecordingState.Recording>>(holder.startRecording())

        val shown = mutableListOf<Long>()
        val scene =
            ImageComposeScene(width = 10, height = 10, coroutineContext = StandardTestDispatcher(testScheduler)) {
                val state by holder.recordingState.collectAsState()
                val durationMs = rememberShownRecordingDurationMs(holder, state)
                SideEffect { shown += durationMs }
            }
        val whileRecording: List<Long>
        try {
            val frameTime = renderFor(scene, Duration.ZERO, 2500.milliseconds)
            whileRecording = shown.toList()
            backgroundScope.launch { holder.pauseRecording() }
            runCurrent()
            assertIs<RecordingState.Paused>(holder.recordingState.value)
            renderFor(scene, frameTime, 2000.milliseconds)
        } finally {
            scene.close()
            holder.close()
        }

        assertEquals(listOf(0L, 1000L, 2000L), whileRecording.distinct(), "the durations shown while recording")
        // The pause shows the time recorded until it, and two seconds later still shows it.
        val held = shown.last()
        assertTrue(held in 2500L..2600L, "the duration held while paused: $held")
        assertEquals(listOf(0L, 1000L, 2000L, held), shown.distinct(), "the durations shown: $shown")
    }

    // Renders a frame every 16 ms for [length] of virtual time from [start]; returns the time reached.
    private fun TestScope.renderFor(scene: ImageComposeScene, start: Duration, length: Duration): Duration {
        var frameTime = start
        while (frameTime < start + length) {
            Snapshot.sendApplyNotifications()
            scene.render(frameTime.inWholeNanoseconds)
            runCurrent()
            advanceTimeBy(FRAME)
            frameTime += FRAME
        }
        return frameTime
    }

    private companion object {
        val FRAME = 16.milliseconds
    }
}

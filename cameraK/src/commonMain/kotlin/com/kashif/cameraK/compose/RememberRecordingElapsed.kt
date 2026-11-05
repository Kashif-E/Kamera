package com.kashif.cameraK.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.kashif.cameraK.recording.RecordingState
import com.kashif.cameraK.recording.elapsed
import com.kashif.cameraK.state.CameraKStateHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The recorded time of [holder]'s recording, from [RecordingState.elapsed]: it moves once per [tick]
 * while recording, holds while paused, and is zero outside a recording. A reader recomposes once per
 * tick, not once per frame.
 *
 * @param tick How often the value moves while recording; positive. One second suits a mm:ss timer.
 *
 * @example
 * ```kotlin
 * val elapsed by rememberRecordingElapsed(holder)
 * Text("${elapsed.inWholeSeconds} s")
 * ```
 */
@Composable
public fun rememberRecordingElapsed(holder: CameraKStateHolder, tick: Duration = 1.seconds): State<Duration> {
    require(tick.isPositive()) { "tick must be positive, was $tick" }
    val elapsed = remember(holder) { mutableStateOf(holder.recordingState.value.elapsed()) }
    LaunchedEffect(holder, tick) {
        holder.recordingState.collectLatest { state ->
            elapsed.value = state.elapsed()
            if (state is RecordingState.Recording) {
                while (true) {
                    delay(tick)
                    elapsed.value = state.elapsed()
                }
            }
        }
    }
    return elapsed
}

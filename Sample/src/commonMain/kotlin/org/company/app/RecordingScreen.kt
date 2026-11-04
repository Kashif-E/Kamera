package org.company.app

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.video.VideoCaptureResult
import com.kashif.cameraK.video.VideoConfiguration
import com.kashif.cameraK.video.VideoQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The Sample's video recording: Full HD with audio, up to five minutes. It records on the 1.x
 * controller until the engines record (A3', I3', D3'); the desktop controller applies no duration
 * limit, so there the five minutes are not enforced.
 */
internal class RecordingFeature {
    val configuration = VideoConfiguration(
        quality = VideoQuality.FHD,
        enableAudio = true,
        maxDuration = 5.minutes,
    )

    // The elapsed time shown while recording: the Sample's own, from when the recording started.
    private var startedAt: TimeMark? = null

    /** The recorded time, or 0 when nothing is recording. */
    val durationMs: Long get() = startedAt?.elapsedNow()?.inWholeMilliseconds ?: 0L

    /** Starts a recording on [controller], or stops the one running; returns whether one runs afterwards. */
    suspend fun toggleRecording(controller: CameraController, isRecording: Boolean): Boolean =
        if (isRecording) stop(controller) else start(controller)

    private suspend fun start(controller: CameraController): Boolean = try {
        val path = controller.startRecording(configuration)
        startedAt = TimeSource.Monotonic.markNow()
        println("Recording to $path")
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("Starting a recording failed: ${e::class.simpleName}")
        false
    }

    private suspend fun stop(controller: CameraController): Boolean {
        startedAt = null
        when (val result = controller.stopRecording()) {
            is VideoCaptureResult.Success -> println("Recording saved: ${result.filePath}")
            is VideoCaptureResult.Error -> println("Recording failed: ${result.exception::class.simpleName}")
        }
        return false
    }
}

@Composable
internal fun rememberRecordingFeature(): RecordingFeature = remember { RecordingFeature() }

/** While [isRecording], publishes the recording's elapsed time to [onDurationChange] four times a second. */
@Composable
internal fun RecordingEffects(recording: RecordingFeature, isRecording: Boolean, onDurationChange: (Long) -> Unit) {
    LaunchedEffect(isRecording) {
        while (isRecording) {
            onDurationChange(recording.durationMs)
            delay(250L)
        }
        onDurationChange(0L)
    }
}

@Composable
internal fun RecordingTimerHud(modifier: Modifier = Modifier, durationMs: Long) {
    val totalSeconds = (durationMs / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val timeText = "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"

    val infiniteTransition = rememberInfiniteTransition()
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotAlpha",
    )

    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(Red.copy(alpha = dotAlpha), CircleShape),
        )
        Text(
            text = timeText,
            color = Red,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

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
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.video.VideoConfiguration
import com.kashif.cameraK.video.VideoQuality
import com.kashif.videorecorderplugin.VideoRecorderPlugin
import com.kashif.videorecorderplugin.rememberVideoRecorderPlugin
import kotlinx.coroutines.delay

/** The Sample's video recording: Full HD with audio, up to five minutes. */
internal class RecordingFeature(val plugin: VideoRecorderPlugin)

@Composable
internal fun rememberRecordingFeature(): RecordingFeature {
    val plugin = rememberVideoRecorderPlugin(
        config = VideoConfiguration(
            quality = VideoQuality.FHD,
            enableAudio = true,
            maxDurationMs = 300_000L,
        ),
    )
    return remember(plugin) { RecordingFeature(plugin) }
}

internal fun RecordingFeature.toggleRecording(isRecording: Boolean) {
    if (isRecording) {
        plugin.stopRecording()
    } else {
        plugin.startRecording()
    }
}

/** Follows the recorder's events into [onRecordingChange] and, while recording, its duration into [onDurationChange]. */
@Composable
internal fun RecordingEffects(
    recording: RecordingFeature,
    isRecording: Boolean,
    onRecordingChange: (Boolean) -> Unit,
    onDurationChange: (Long) -> Unit,
) {
    LaunchedEffect(recording) {
        recording.plugin.recordingEvents.collect { event ->
            when (event) {
                is CameraKEvent.RecordingStarted -> {
                    onRecordingChange(true)
                }

                is CameraKEvent.RecordingStopped,
                is CameraKEvent.RecordingFailed,
                is CameraKEvent.RecordingMaxDurationReached,
                -> {
                    onRecordingChange(false)
                    onDurationChange(0L)
                }

                else -> {}
            }
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (true) {
                onDurationChange(recording.plugin.recordingDurationMs)
                delay(250L)
            }
        }
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

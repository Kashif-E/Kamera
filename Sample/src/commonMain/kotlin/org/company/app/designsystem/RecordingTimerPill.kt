package org.company.app.designsystem

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp

/** The red pill that shows the recorded time, as "00:42", with a blinking dot. */
@Composable
internal fun RecordingTimerPill(durationMs: Long, modifier: Modifier = Modifier) {
    val colors = SampleTheme.colors
    Row(
        modifier = modifier
            .height(SampleTheme.sizes.pill)
            .clip(SampleTheme.shapes.pill)
            .background(colors.recording)
            .padding(horizontal = SampleTheme.spacing.md + SampleTheme.spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(SampleTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(DOT)
                .alpha(blinkingAlpha())
                .clip(SampleTheme.shapes.circle)
                .background(colors.onRecording),
        )
        Text(text = recordingTime(durationMs), style = SampleTheme.typography.timer, color = colors.onRecording)
    }
}

/** [durationMs] as minutes and seconds, "mm:ss". */
internal fun recordingTime(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = (totalSeconds / 60).toString().padStart(2, '0')
    val seconds = (totalSeconds % 60).toString().padStart(2, '0')
    return "$minutes:$seconds"
}

// A preview shows the dot still: an infinite animation never lets it settle.
@Composable
private fun blinkingAlpha(): Float {
    if (LocalInspectionMode.current) return 1f
    val alpha by rememberInfiniteTransition().animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(BLINK_MS, easing = EaseInOut), RepeatMode.Reverse),
    )
    return alpha
}

private val DOT = 8.dp
private const val BLINK_MS = 1000

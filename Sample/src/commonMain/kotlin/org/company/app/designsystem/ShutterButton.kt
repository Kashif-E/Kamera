package org.company.app.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** What the shutter does when tapped. */
internal enum class ShutterVariant(val label: String) {
    Photo("Take photo"),
    Video("Start recording"),
    Recording("Stop recording"),
}

/**
 * The capture button: an indigo disc for a photo, a red disc to start a recording, and a red square
 * in a dark ring to stop one. It is dimmed and ignores taps while [enabled] is false.
 */
@Composable
internal fun ShutterButton(
    variant: ShutterVariant,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = SampleTheme.colors
    val sizes = SampleTheme.sizes
    val recording = variant == ShutterVariant.Recording
    // The ring's stroke and the gap inside it leave the disc this much smaller than the button.
    val disc = sizes.shutter - (sizes.strokeBold + DISC_GAP) * 2
    val ring by animateColorAsState(if (recording) colors.ink else colors.primary)
    val fill by animateColorAsState(if (variant == ShutterVariant.Photo) colors.primary else colors.recording)
    val innerSize by animateDpAsState(if (recording) STOP_SQUARE else disc)
    val innerCorner by animateDpAsState(if (recording) STOP_CORNER else disc / 2)
    Box(
        modifier = modifier
            .size(sizes.shutter)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .border(sizes.strokeBold, ring, SampleTheme.shapes.circle)
            .clip(SampleTheme.shapes.circle)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = variant.label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(innerSize).background(fill, RoundedCornerShape(innerCorner)))
    }
}

private val DISC_GAP = 5.dp
private val STOP_SQUARE = 28.dp
private val STOP_CORNER = 6.dp
private const val DISABLED_ALPHA = 0.5f

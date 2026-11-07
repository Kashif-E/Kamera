package org.company.app.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A centred line of status, with an [icon] above it when there is one, such as the camera's error. */
@Composable
internal fun StatusMessage(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconDescription: String? = null,
) {
    Column(
        modifier = modifier.padding(SampleTheme.spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SampleTheme.spacing.lg),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = iconDescription,
                tint = SampleTheme.colors.recording,
                modifier = Modifier.size(SampleTheme.sizes.touchTarget),
            )
        }
        Text(
            text = text,
            style = SampleTheme.typography.bodyLarge,
            color = SampleTheme.colors.inkBody,
            textAlign = TextAlign.Center,
        )
    }
}

/** One line of a short list, after a dot: indigo for what is always so, grey for what only sometimes is. */
@Composable
internal fun BulletRow(text: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(SampleTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(BULLET)
                .clip(SampleTheme.shapes.circle)
                .background(if (accent) SampleTheme.colors.primary else SampleTheme.colors.disabled),
        )
        Text(text = text, style = SampleTheme.typography.bodySmall, color = SampleTheme.colors.inkBody)
    }
}

/** An indigo icon on a rounded grey tile, at the head of a full screen such as the permission one. */
@Composable
internal fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(SampleTheme.sizes.badge)
            .clip(SampleTheme.shapes.badge)
            .background(SampleTheme.colors.control),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = SampleTheme.colors.primary,
            modifier = Modifier.size(BADGE_ICON),
        )
    }
}

/**
 * A white chip over the preview that says something was detected, [label] and [value], and opens the
 * mode that shows it when tapped.
 */
@Composable
internal fun DetectionChip(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = SampleTheme.sizes.touchTarget)
            .clip(SampleTheme.shapes.pill)
            .background(SampleTheme.colors.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = SampleTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(SampleTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Eyebrow(text = label, color = SampleTheme.colors.primary)
        Text(
            text = value,
            style = SampleTheme.typography.chipQuiet,
            color = SampleTheme.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A line, and a quieter [detail] under it, on a pale card over the preview, readable over any image,
 * as the QR mode's "Point at a QR code".
 */
@Composable
internal fun OverlayLabel(text: String, modifier: Modifier = Modifier, detail: String? = null) {
    Column(
        modifier = modifier
            .clip(SampleTheme.shapes.option)
            .background(SampleTheme.colors.background.copy(alpha = OVERLAY_ALPHA))
            .padding(horizontal = SampleTheme.spacing.md, vertical = SampleTheme.spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = text, style = SampleTheme.typography.bodySmall, color = SampleTheme.colors.ink)
        if (detail != null) {
            Text(text = detail, style = SampleTheme.typography.caption, color = SampleTheme.colors.inkOnPreview)
        }
    }
}

/** The Sample's snackbars: dark ink on the light screens. */
@Composable
internal fun SampleSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Snackbar(
            snackbarData = data,
            shape = SampleTheme.shapes.button,
            containerColor = SampleTheme.colors.ink,
            contentColor = SampleTheme.colors.background,
        )
    }
}

private val BULLET = 6.dp
private const val OVERLAY_ALPHA = 0.85f
private val BADGE_ICON = 30.dp

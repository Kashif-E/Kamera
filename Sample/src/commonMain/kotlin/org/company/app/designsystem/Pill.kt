package org.company.app.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp

/** How a [Pill] looks. */
@Immutable
internal data class PillStyle(val container: Color, val content: Color, val textStyle: TextStyle, val minWidth: Dp)

internal object PillDefaults {
    /** A grey pill with dark text, such as the aspect-ratio chip. */
    @Composable
    fun neutral(): PillStyle = PillStyle(
        container = SampleTheme.colors.control,
        content = SampleTheme.colors.ink,
        textStyle = SampleTheme.typography.chip,
        minWidth = SampleTheme.sizes.touchTarget,
    )

    /** One choice among pills: indigo when chosen, quiet grey otherwise. */
    @Composable
    fun choice(selected: Boolean): PillStyle = if (selected) {
        PillStyle(
            container = SampleTheme.colors.primary,
            content = SampleTheme.colors.onPrimary,
            textStyle = SampleTheme.typography.chip,
            minWidth = SampleTheme.sizes.touchTarget + SampleTheme.spacing.sm,
        )
    } else {
        PillStyle(
            container = SampleTheme.colors.control,
            content = SampleTheme.colors.inkSecondary,
            textStyle = SampleTheme.typography.chipQuiet,
            minWidth = SampleTheme.sizes.touchTarget,
        )
    }
}

/**
 * A 32 dp text pill whose touch area is at least the touch target. [selected], when not null, makes it
 * one choice of a group; [contentDescription] replaces the text for accessibility services.
 */
@Composable
internal fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillDefaults.neutral(),
    selected: Boolean? = null,
    contentDescription: String? = null,
) {
    val interaction = if (selected == null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
    }
    val described = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .then(interaction)
            .then(described),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(SampleTheme.sizes.pill)
                .widthIn(min = style.minWidth)
                .clip(SampleTheme.shapes.pill)
                .background(style.container)
                .padding(horizontal = SampleTheme.spacing.md),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = text, style = style.textStyle, color = style.content, maxLines = 1)
        }
    }
}

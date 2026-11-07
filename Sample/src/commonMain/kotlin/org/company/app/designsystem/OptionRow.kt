package org.company.app.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow

/** One choice of an [OptionRow]: indigo when [selected], grey otherwise. */
@Composable
internal fun OptionButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = SampleTheme.colors
    val container = when {
        selected && enabled -> colors.primary
        selected -> colors.primary.copy(alpha = DISABLED_SELECTED_ALPHA)
        else -> colors.control
    }
    val content = when {
        selected -> colors.onPrimary
        enabled -> colors.ink
        else -> colors.disabled
    }
    Box(
        modifier = modifier
            .heightIn(min = SampleTheme.sizes.option)
            .clip(SampleTheme.shapes.option)
            .background(container)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = SampleTheme.spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = if (selected) SampleTheme.typography.optionSelected else SampleTheme.typography.option,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A setting as an eyebrow [label] over its [options], equal widths, the [selected] one marked. Up to
 * four share a row; more wrap in rows of three.
 */
@Composable
internal fun <T> OptionRow(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    optionLabel: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val spacing = SampleTheme.spacing.sm
    val perRow = if (options.size <= MAX_IN_ONE_ROW) options.size else WRAPPED_ROW
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        Eyebrow(text = label)
        Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(spacing)) {
            options.chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    row.forEach { option ->
                        OptionButton(
                            text = optionLabel(option),
                            selected = option == selected,
                            onClick = { onSelect(option) },
                            modifier = Modifier.weight(1f),
                            enabled = enabled,
                        )
                    }
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private const val MAX_IN_ONE_ROW = 4
private const val WRAPPED_ROW = 3
private const val DISABLED_SELECTED_ALPHA = 0.45f

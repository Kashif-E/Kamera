package org.company.app.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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

/** Two or three short choices in one grey capsule, the [selected] one an indigo pill. */
@Composable
internal fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    val colors = SampleTheme.colors
    val inset = SampleTheme.spacing.inset
    Row(
        modifier = modifier
            .clip(SampleTheme.shapes.pill)
            .background(colors.control)
            .padding(inset)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                // The capsule's inset brings each segment's touch area up to the touch target.
                modifier = Modifier
                    .heightIn(min = SampleTheme.sizes.touchTarget - inset * 2)
                    .clip(SampleTheme.shapes.pill)
                    .background(if (isSelected) colors.primary else colors.control)
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(option) }
                    .padding(horizontal = SampleTheme.spacing.md),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = if (isSelected) SampleTheme.typography.chip else SampleTheme.typography.chipQuiet,
                    color = if (isSelected) colors.onPrimary else colors.inkSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

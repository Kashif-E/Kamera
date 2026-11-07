package org.company.app.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/**
 * The camera's modes as a row of uppercase labels, the selected one indigo. Tapping one calls
 * [onSelect] with it; while [enabled] is false nothing can be picked.
 */
@Composable
internal fun <T> ModeSwitcher(
    modes: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        modes.forEach { mode ->
            val isSelected = mode == selected
            val color = when {
                isSelected -> SampleTheme.colors.primary
                enabled -> SampleTheme.colors.inkSecondary
                else -> SampleTheme.colors.disabled
            }
            Box(
                // Half the gap on each side, so the labels sit the design's gap apart and each touch
                // area reaches the touch target.
                modifier = Modifier
                    .heightIn(min = SampleTheme.sizes.touchTarget)
                    .widthIn(min = SampleTheme.sizes.touchTarget)
                    .selectable(selected = isSelected, enabled = enabled, role = Role.Tab) { onSelect(mode) }
                    .padding(horizontal = SampleTheme.spacing.modeGap / 2),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = label(mode), style = SampleTheme.typography.mode, color = color, maxLines = 1)
            }
        }
    }
}

package org.company.app.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role

/** A full-width button's height, corners and text: [Large] is the one call to action of a screen. */
internal enum class ButtonSize { Regular, Large }

/** The indigo button for a screen's or a sheet's main action. */
@Composable
internal fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: ButtonSize = ButtonSize.Regular,
) {
    SampleButton(text, onClick, modifier, enabled, size, SampleTheme.colors.primary, SampleTheme.colors.onPrimary)
}

/** The grey button for a secondary action beside or below a [PrimaryButton]. */
@Composable
internal fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: ButtonSize = ButtonSize.Regular,
) {
    SampleButton(text, onClick, modifier, enabled, size, SampleTheme.colors.control, SampleTheme.colors.ink)
}

@Composable
private fun SampleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    size: ButtonSize,
    container: Color,
    content: Color,
) {
    val large = size == ButtonSize.Large
    Box(
        modifier = modifier
            .height(if (large) SampleTheme.sizes.buttonLarge else SampleTheme.sizes.button)
            .clip(if (large) SampleTheme.shapes.buttonLarge else SampleTheme.shapes.button)
            .background(if (enabled) container else SampleTheme.colors.control)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = SampleTheme.spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = if (large) SampleTheme.typography.buttonLarge else SampleTheme.typography.button,
            color = if (enabled) content else SampleTheme.colors.disabled,
            maxLines = 1,
        )
    }
}

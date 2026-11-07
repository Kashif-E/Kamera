package org.company.app.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp

/** How a [CircleIconButton] looks: its diameter, icon size and colours. */
@Immutable
internal data class CircleIconButtonStyle(
    val size: Dp,
    val iconSize: Dp,
    val container: Color,
    val content: Color,
    val disabledContent: Color,
)

internal object CircleIconButtonDefaults {
    /** The grey 44 dp button of the top bars. */
    @Composable
    fun style(): CircleIconButtonStyle = CircleIconButtonStyle(
        size = SampleTheme.sizes.circleButton,
        iconSize = SampleTheme.sizes.icon,
        container = SampleTheme.colors.control,
        content = SampleTheme.colors.ink,
        disabledContent = SampleTheme.colors.disabled,
    )

    /** The 48 dp button beside the shutter. */
    @Composable
    fun control(): CircleIconButtonStyle =
        style().copy(size = SampleTheme.sizes.controlButton, iconSize = SampleTheme.sizes.iconLarge)

    /** A button whose setting is on, such as the torch. */
    @Composable
    fun active(): CircleIconButtonStyle =
        style().copy(container = SampleTheme.colors.primary, content = SampleTheme.colors.onPrimary)
}

/** A round icon button, labelled for accessibility by [contentDescription]. */
@Composable
internal fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: CircleIconButtonStyle = CircleIconButtonDefaults.style(),
) {
    Box(
        modifier = modifier
            .size(style.size)
            .clip(SampleTheme.shapes.circle)
            .background(style.container)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) style.content else style.disabledContent,
            modifier = Modifier.size(style.iconSize),
        )
    }
}

package org.company.app.designsystem

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** A small uppercase label above or before what it names, as "ASPECT RATIO". */
@Composable
internal fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = SampleTheme.colors.inkSecondary) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = SampleTheme.typography.eyebrow,
        color = color,
        maxLines = 1,
    )
}

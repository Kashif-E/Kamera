package org.company.app.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * The bar over the preview, built from parts: [leading] items from the start (a back button, a
 * title, the flash), [trailing] items at the end (settings, the torch), and [center], when given,
 * in the middle (the recording timer).
 */
@Composable
internal fun CameraTopBar(
    modifier: Modifier = Modifier,
    leading: List<@Composable () -> Unit> = emptyList(),
    trailing: List<@Composable () -> Unit> = emptyList(),
    center: (@Composable () -> Unit)? = null,
) {
    val spacing = SampleTheme.spacing
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SampleTheme.sizes.topBar)
            .padding(start = spacing.lg, top = spacing.lg, end = spacing.lg),
    ) {
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) { leading.forEach { it() } }
        if (center != null) Box(Modifier.align(Alignment.Center)) { center() }
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) { trailing.forEach { it() } }
    }
}

/** A screen's name in the [CameraTopBar], as "QR Scanner". */
@Composable
internal fun TopBarTitle(text: String, modifier: Modifier = Modifier) {
    Text(text = text, modifier = modifier, style = SampleTheme.typography.screenTitle, color = SampleTheme.colors.ink)
}

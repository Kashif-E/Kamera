package org.company.app.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * The controls under the preview, in the design's places: [above] (the zoom pills, or a note while
 * recording), the [modes], and a row of [start], [shutter] and [end]. A slot left empty keeps its
 * place, so nothing moves when a control comes or goes.
 */
@Composable
internal fun CaptureDock(
    modifier: Modifier = Modifier,
    above: @Composable () -> Unit = {},
    modes: @Composable () -> Unit = {},
    start: @Composable () -> Unit = {},
    shutter: @Composable () -> Unit = {},
    end: @Composable () -> Unit = {},
) {
    val spacing = SampleTheme.spacing
    val sizes = SampleTheme.sizes
    Column(
        modifier = modifier
            .height(sizes.controlsArea)
            .padding(top = spacing.sm, bottom = spacing.controlsBottom),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.heightIn(min = sizes.touchTarget), contentAlignment = Alignment.Center) { above() }
        Spacer(Modifier.height(spacing.xs))
        Box(Modifier.heightIn(min = sizes.touchTarget), contentAlignment = Alignment.Center) { modes() }
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.controlsEdge),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(sizes.controlButton), contentAlignment = Alignment.Center) { start() }
            Box(Modifier.size(sizes.shutter), contentAlignment = Alignment.Center) { shutter() }
            Box(Modifier.size(sizes.controlButton), contentAlignment = Alignment.Center) { end() }
        }
    }
}

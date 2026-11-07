package org.company.app.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One pill per zoom stop; the stop the zoom sits on is indigo and reads with a ×, as "1×". A zoom
 * between stops, from a pinch, marks none.
 */
@Composable
internal fun ZoomPills(stops: List<Float>, zoom: Float, onSelect: (Float) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(SampleTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stops.forEach { stop ->
            val selected = abs(zoom - stop) < ZOOM_TOLERANCE
            val label = zoomLabel(stop)
            Pill(
                text = if (selected) "$label×" else label,
                onClick = { onSelect(stop) },
                style = PillDefaults.choice(selected),
                selected = selected,
                contentDescription = "Zoom $label×",
            )
        }
    }
}

/** The zoom stops for a camera's range: below 1 when it goes wider, 1, 2, 4 and its longest. */
internal fun zoomStops(minRatio: Float, maxRatio: Float): List<Float> = buildList {
    if (minRatio < 1f - ZOOM_TOLERANCE) add(minRatio)
    add(1f)
    if (maxRatio >= 2f) add(2f)
    if (maxRatio >= 4f) add(4f)
    if (maxRatio > 4f + ZOOM_TOLERANCE) add(maxRatio)
}

// A whole stop reads "2", any other one decimal, as "0.5".
internal fun zoomLabel(stop: Float): String {
    val tenths = (stop * 10).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
}

private const val ZOOM_TOLERANCE = 0.05f

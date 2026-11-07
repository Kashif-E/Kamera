package org.company.app.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** The rule-of-thirds grid over the preview. */
@Composable
internal fun ThirdsGrid(modifier: Modifier = Modifier) {
    val color = SampleTheme.colors.previewLine
    val stroke = SampleTheme.sizes.hairline
    Canvas(modifier.fillMaxSize()) {
        val width = stroke.toPx()
        for (third in 1..2) {
            val x = size.width * third / 3
            val y = size.height * third / 3
            drawLine(color, Offset(x, 0f), Offset(x, size.height), width)
            drawLine(color, Offset(0f, y), Offset(size.width, y), width)
        }
    }
}

/** Four rounded corners that frame where to hold a code. It aims the camera; the scanner reads the whole frame. */
@Composable
internal fun ScanReticle(modifier: Modifier = Modifier, color: Color = SampleTheme.colors.primary) {
    val stroke = SampleTheme.sizes.strokeBold
    Canvas(modifier.size(SampleTheme.sizes.reticle)) {
        val width = stroke.toPx()
        val inset = width / 2
        val arm = ARM.toPx()
        val radius = CORNER.toPx()
        val right = size.width - inset
        val bottom = size.height - inset
        // Each corner: along one edge to the arc, round the arc, along the other edge.
        val corners = listOf(
            Triple(Offset(inset, inset), 1f, 1f),
            Triple(Offset(right, inset), -1f, 1f),
            Triple(Offset(inset, bottom), 1f, -1f),
            Triple(Offset(right, bottom), -1f, -1f),
        )
        corners.forEach { (corner, dx, dy) ->
            val path = Path().apply {
                moveTo(corner.x, corner.y + dy * arm)
                lineTo(corner.x, corner.y + dy * radius)
                val oval = Rect(
                    left = minOf(corner.x, corner.x + dx * 2 * radius),
                    top = minOf(corner.y, corner.y + dy * 2 * radius),
                    right = maxOf(corner.x, corner.x + dx * 2 * radius),
                    bottom = maxOf(corner.y, corner.y + dy * 2 * radius),
                )
                arcTo(oval, startAngleDegrees = startAngle(dx), sweepAngleDegrees = sweep(dx, dy), forceMoveTo = false)
                lineTo(corner.x + dx * arm, corner.y)
            }
            drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))
        }
    }
}

// The arc runs from the corner's vertical edge (the oval's left or right point) to its horizontal one.
private fun startAngle(dx: Float): Float = if (dx > 0) 180f else 0f

private fun sweep(dx: Float, dy: Float): Float = if (dx * dy > 0) 90f else -90f

private val ARM = 36.dp
private val CORNER = 14.dp

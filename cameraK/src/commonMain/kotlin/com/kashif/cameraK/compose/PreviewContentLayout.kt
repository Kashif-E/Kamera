package com.kashif.cameraK.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import com.kashif.cameraK.enums.DeviceOrientation
import kotlin.math.roundToInt

/**
 * The preview's content rectangle in a box of [box]: the largest centred box of [contentAspect]
 * (width over height), or the whole box when it is null, which fills it. An aspect ratio that is not
 * a positive number also fills the box.
 */
internal fun contentRect(box: Size, contentAspect: Float?): Rect {
    val aspect = usableAspect(contentAspect) ?: return Rect(Offset.Zero, box)
    val (width, height) = if (box.width > box.height * aspect) {
        box.height * aspect to box.height
    } else {
        box.width to box.width / aspect
    }
    val left = (box.width - width) / 2f
    val top = (box.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

/**
 * The one preview layout every platform's EnginePreview uses: [surface] and [overlay] share the
 * content rectangle, [contentRect] of this layout's box and [contentAspect], and the bars outside it
 * stay empty. [contentAspect] gets the box before the rectangle is taken, so a platform that turns its
 * ratio by the box's shape can, and it is read while measuring, so a state it reads moves the
 * rectangle without recomposing anything.
 *
 * Inside bounded constraints the box is their maximum, shrunk to the content where the constraints
 * allow, as a Box around an aspect-ratio child is. The rectangle's sides are rounded to whole pixels
 * and centred as Alignment.Center centres them.
 */
@Composable
internal fun PreviewContent(
    contentAspect: (box: Size) -> Float?,
    deviceOrientation: DeviceOrientation,
    overlay: (@Composable CameraPreviewScope.() -> Unit)?,
    modifier: Modifier = Modifier,
    surface: (@Composable BoxScope.() -> Unit)? = null,
) {
    Layout(
        content = {
            if (surface != null) Box(content = surface)
            if (overlay != null) {
                BoxWithConstraints {
                    val bounds = Rect(0f, 0f, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                    CameraPreviewOverlay(deviceOrientation, bounds, overlay)
                }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val aspect = contentAspect(Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()))
        val content = contentRect(available(constraints, aspect), aspect)
        val width = content.width.roundToInt()
        val height = content.height.roundToInt()
        val placeables = measurables.map { it.measure(Constraints.fixed(width, height)) }
        val layoutWidth = constraints.constrainWidth(width)
        val layoutHeight = constraints.constrainHeight(height)
        val x = ((layoutWidth - width) / 2f).roundToInt()
        val y = ((layoutHeight - height) / 2f).roundToInt()
        layout(layoutWidth, layoutHeight) {
            placeables.forEach { it.place(x, y) }
        }
    }
}

private fun usableAspect(aspect: Float?): Float? = aspect?.takeIf { it.isFinite() && it > 0f }

/**
 * The box to fit the content in: the bounded maximum, or along an unbounded side what [contentAspect]
 * makes of the other side, or the minimum when neither gives a size.
 */
private fun available(constraints: Constraints, contentAspect: Float?): Size {
    val aspect = usableAspect(contentAspect)
    val width = when {
        constraints.hasBoundedWidth -> constraints.maxWidth.toFloat()
        aspect != null && constraints.hasBoundedHeight -> constraints.maxHeight * aspect
        else -> constraints.minWidth.toFloat()
    }
    val height = when {
        constraints.hasBoundedHeight -> constraints.maxHeight.toFloat()
        aspect != null && constraints.hasBoundedWidth -> constraints.maxWidth / aspect
        else -> constraints.minHeight.toFloat()
    }
    return Size(width, height)
}

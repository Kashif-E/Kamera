package org.company.app.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Sun
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A vertical exposure slider showing [index] in [range], each step [step] EV, brighter towards the
 * top. Dragging it calls [onChange] with each new index under the finger, measured from where the drag
 * started; tapping its track calls it with the index at the tap. It shows [index] whatever the drag
 * did, so a caller that passes the camera's read-back shows what the camera applied. [interactionSource]
 * receives its drags.
 */
@Composable
internal fun ExposureSlider(
    index: Int,
    range: IntRange,
    step: Float,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
) {
    val steps = range.last - range.first
    val shown = index.coerceIn(range)
    val currentIndex by rememberUpdatedState(shown)
    val currentOnChange by rememberUpdatedState(onChange)
    val track = SampleTheme.colors.onCamera.copy(alpha = TRACK_ALPHA)
    val input = if (steps > 0) {
        Modifier
            .pointerInput(range, interactionSource) {
                val stepPx = TRACK_LENGTH.toPx() / steps
                var anchor = 0
                var dragged = 0f
                var last = 0
                var start: DragInteraction.Start? = null
                fun stop(end: (DragInteraction.Start) -> DragInteraction) {
                    start?.let { interactionSource?.tryEmit(end(it)) }
                    start = null
                }
                detectVerticalDragGestures(
                    onDragStart = {
                        anchor = currentIndex
                        last = anchor
                        dragged = 0f
                        start = DragInteraction.Start().also { interactionSource?.tryEmit(it) }
                    },
                    onDragEnd = { stop(DragInteraction::Stop) },
                    onDragCancel = { stop(DragInteraction::Cancel) },
                ) { change, dy ->
                    change.consume()
                    dragged += dy
                    val next = (anchor - (dragged / stepPx).roundToInt()).coerceIn(range)
                    if (next != last) {
                        last = next
                        currentOnChange(next)
                    }
                }
            }
            .pointerInput(range) {
                detectTapGestures { tap ->
                    val fromTop = ((tap.y - THUMB_SIZE.toPx() / 2) / TRACK_LENGTH.toPx()).coerceIn(0f, 1f)
                    currentOnChange(range.last - (fromTop * steps).roundToInt())
                }
            }
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .size(width = THUMB_SIZE, height = TRACK_LENGTH + THUMB_SIZE)
            .semantics {
                contentDescription = "Exposure"
                stateDescription = exposureLabel(shown, step)
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = shown.toFloat(),
                    range = range.first.toFloat()..range.last.toFloat(),
                    steps = (steps - 1).coerceAtLeast(0),
                )
                if (steps > 0) {
                    setProgress { target ->
                        currentOnChange(target.roundToInt().coerceIn(range))
                        true
                    }
                }
            }
            .then(input)
            .drawBehind {
                val x = size.width / 2
                val top = THUMB_SIZE.toPx() / 2
                drawLine(track, Offset(x, top), Offset(x, top + TRACK_LENGTH.toPx()), TRACK_WIDTH.toPx())
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        val fromTop = if (steps > 0) (range.last - shown).toFloat() / steps else 0.5f
        Box(
            modifier = Modifier
                .offset { IntOffset(0, (fromTop * TRACK_LENGTH.toPx()).roundToInt()) }
                .size(THUMB_SIZE)
                .clip(SampleTheme.shapes.circle)
                .background(SampleTheme.colors.onCamera),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Lucide.Sun,
                contentDescription = null,
                tint = SampleTheme.colors.ink,
                modifier = Modifier.size(ICON_SIZE),
            )
        }
    }
}

/** [index] steps of [step] EV as the slider reads it out, to one decimal: "+1.0 EV". */
internal fun exposureLabel(index: Int, step: Float): String {
    val tenths = (index * step * TENTHS).roundToInt()
    val sign = when {
        tenths > 0 -> "+"
        tenths < 0 -> "-"
        else -> ""
    }
    val magnitude = abs(tenths)
    return "$sign${magnitude / TENTHS}.${magnitude % TENTHS} EV"
}

private const val TENTHS = 10
private const val TRACK_ALPHA = 0.6f
private val TRACK_LENGTH = 128.dp
private val TRACK_WIDTH = 2.dp
private val THUMB_SIZE = 28.dp
private val ICON_SIZE = 16.dp

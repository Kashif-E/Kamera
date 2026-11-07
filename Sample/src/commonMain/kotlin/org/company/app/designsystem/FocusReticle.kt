package org.company.app.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kashif.cameraK.compose.FocusOutcome
import com.kashif.cameraK.compose.FocusTap
import kotlin.math.roundToInt

/**
 * The focus reticle at [tap], for `CameraPreview`'s focus indicator: a rounded square centred on the
 * tap, white while the camera focuses, indigo once it has, grey when it could not. It only draws; when
 * it shows and hides is the preview's.
 */
@Composable
internal fun FocusReticle(tap: FocusTap, modifier: Modifier = Modifier) {
    val pending = tap.outcome == FocusOutcome.PENDING
    // A new tap settles from 1.15 to its size; the answer to it keeps the size it has.
    val scale = remember(tap.position, pending) { Animatable(if (pending) PENDING_SCALE else 1f) }
    LaunchedEffect(scale) { scale.animateTo(1f, tween(SETTLE_MS)) }
    val stroke = when (tap.outcome) {
        FocusOutcome.PENDING -> SampleTheme.colors.onCamera.copy(alpha = PENDING_ALPHA)
        FocusOutcome.FOCUSED -> SampleTheme.colors.primary
        FocusOutcome.FAILED -> SampleTheme.colors.disabled
    }
    val hairline = if (tap.outcome == FocusOutcome.FOCUSED) SampleTheme.colors.onCamera else null
    Box(
        modifier = modifier
            .offset {
                val half = (RETICLE_SIZE / 2).toPx()
                IntOffset((tap.position.x - half).roundToInt(), (tap.position.y - half).roundToInt())
            }
            .size(RETICLE_SIZE)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .semantics {
                contentDescription = "Focus point"
                stateDescription = when (tap.outcome) {
                    FocusOutcome.PENDING -> "Focusing"
                    FocusOutcome.FOCUSED -> "Focused"
                    FocusOutcome.FAILED -> "Could not focus"
                }
            }
            .drawBehind {
                var inset = 0f
                if (hairline != null) {
                    outline(hairline, inset, HAIRLINE.toPx())
                    inset += HAIRLINE.toPx()
                }
                outline(stroke, inset, STROKE.toPx())
            },
    )
}

// A rounded-square outline [width] wide, its outer edge [inset] in from the box's.
private fun DrawScope.outline(color: Color, inset: Float, width: Float) {
    val middle = inset + width / 2
    val radius = (CORNER.toPx() - middle).coerceAtLeast(0f)
    drawRoundRect(
        color = color,
        topLeft = Offset(middle, middle),
        size = Size(size.width - 2 * middle, size.height - 2 * middle),
        cornerRadius = CornerRadius(radius),
        style = Stroke(width),
    )
}

private val RETICLE_SIZE = 72.dp
private val CORNER = 12.dp
private val STROKE = 1.5.dp

// Keeps the indigo reticle visible on a dark scene.
private val HAIRLINE = 0.5.dp
private const val PENDING_ALPHA = 0.9f
private const val PENDING_SCALE = 1.15f
private const val SETTLE_MS = 200

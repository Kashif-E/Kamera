package com.kashif.cameraK.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.controls.NormalizedPoint
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraKStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/** How long the focus indicator stays after the camera answered (w12 G4). */
internal val FOCUS_INDICATOR_HOLD = 1_500.milliseconds

/**
 * [overlay] in a box over this content rectangle that turns a tap into `holder.focusAt` at its
 * fraction of the rectangle, with [focusIndicator] drawn over the overlay for the latest tap. Only
 * while the bound camera supports a focus point: otherwise a tap does nothing and no indicator shows.
 * The tap is handled after the overlay's own elements, so one that handles it keeps it.
 */
@Composable
internal fun CameraPreviewScope.TapToFocus(
    holder: CameraKStateHolder,
    focusIndicator: @Composable (FocusTap) -> Unit,
    overlay: @Composable CameraPreviewScope.() -> Unit,
) {
    val supportsFocus = remember(holder) {
        combine(holder.capabilities, holder.uiState) { capabilities, ui -> capabilities.focusPointOn(ui.cameraId) }
            .distinctUntilChanged()
    }
    val supported by supportsFocus.collectAsState(holder.capabilities.value.focusPointOn(holder.uiState.value.cameraId))
    val scope = rememberCoroutineScope()
    val state = remember(holder) { FocusTapState() }
    val taps = if (supported) {
        Modifier.pointerInput(holder, state) { detectTapGestures { state.onTap(scope, holder, it, size) } }
    } else {
        Modifier
    }
    val preview = this
    Box(Modifier.matchParentSize().then(taps)) {
        preview.overlay()
        val tap = state.tap
        if (supported && tap != null) focusIndicator(tap)
    }
}

/** A ring at the tap, faded when the camera did not take the point. */
@Composable
internal fun DefaultFocusIndicator(tap: FocusTap) {
    val alpha = if (tap.outcome == FocusOutcome.FAILED) FAILED_ALPHA else 1f
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(
            color = Color.White.copy(alpha = alpha),
            radius = INDICATOR_RADIUS.toPx(),
            center = tap.position,
            style = Stroke(width = INDICATOR_STROKE.toPx()),
        )
    }
}

private val INDICATOR_RADIUS = 32.dp
private val INDICATOR_STROKE = 2.dp
private const val FAILED_ALPHA = 0.4f

private fun CameraCapabilities.focusPointOn(cameraId: String?): Boolean =
    cameraId != null && supportsFocusPoint(cameraId)

/** The latest tap and the request it made; a new tap replaces both. Used on the composition's thread. */
@Stable
private class FocusTapState {
    var tap: FocusTap? by mutableStateOf(null)
        private set
    private var request: Job? = null

    fun onTap(scope: CoroutineScope, holder: CameraKStateHolder, position: Offset, size: IntSize) {
        if (size.width <= 0 || size.height <= 0) return
        val point = NormalizedPoint(
            (position.x / size.width).coerceIn(0f, 1f),
            (position.y / size.height).coerceIn(0f, 1f),
        )
        request?.cancel()
        tap = FocusTap(position, point, FocusOutcome.PENDING)
        request = scope.launch {
            val focused = holder.focusAt(point) is CameraKResult.Success
            tap = FocusTap(position, point, if (focused) FocusOutcome.FOCUSED else FocusOutcome.FAILED)
            delay(FOCUS_INDICATOR_HOLD)
            tap = null
        }
    }
}

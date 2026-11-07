package org.company.app

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kashif.cameraK.compose.CameraPreviewScope
import com.kashif.cameraK.compose.FocusOutcome
import com.kashif.cameraK.compose.FocusTap
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.company.app.designsystem.ExposureSlider
import org.company.app.designsystem.FocusReticle
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * Where the last focus tap landed, for the exposure slider beside it. The preview's focus indicator
 * reports each new tap; used on the composition's thread.
 */
@Stable
internal class FocusTapRecord {
    /** The last tap's place in the content rectangle, in pixels; null before the first. */
    var position: Offset? by mutableStateOf(null)
        private set

    /** Counts the taps, so the same place tapped twice is a new tap. */
    var taps: Int by mutableIntStateOf(0)
        private set

    fun record(tap: FocusTap) {
        position = tap.position
        taps++
    }
}

/** The Sample's focus indicator: the reticle, and a new tap recorded in [record] for the slider. */
@Composable
internal fun SampleFocusIndicator(tap: FocusTap, record: FocusTapRecord) {
    FocusReticle(tap)
    if (tap.outcome == FocusOutcome.PENDING) {
        LaunchedEffect(tap) { record.record(tap) }
    }
}

/**
 * The exposure slider beside the last focus tap, on a camera with exposure compensation: shown for
 * 3 s from the tap or while it is dragged. A drag sets the index on [holder]; the slider shows the
 * dragged index until the camera answers, then the index the camera reports.
 */
@Composable
internal fun CameraPreviewScope.ExposureControl(holder: CameraKStateHolder, record: FocusTapRecord) {
    val interactions = remember { MutableInteractionSource() }
    val dragged by interactions.collectIsDraggedAsState()
    var recent by remember { mutableStateOf(false) }
    LaunchedEffect(record.taps) {
        if (record.taps == 0) return@LaunchedEffect
        recent = true
        delay(SLIDER_SHOWN_AFTER_TAP)
        recent = false
    }
    var requested by remember { mutableStateOf<Int?>(null) }
    var latestRequest by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val ui by holder.uiState.collectAsState()
    val capabilities by holder.capabilities.collectAsState()
    val cameraId = ui.cameraId ?: return
    val range = capabilities.exposureCompensationRange(cameraId) ?: return
    val step = capabilities.exposureCompensationStep(cameraId) ?: return
    val tap = record.position ?: return
    if (!recent && !dragged) return

    ExposureSlider(
        index = requested ?: ui.exposureCompensationIndex,
        range = range,
        step = step,
        onChange = { index ->
            requested = index
            val request = ++latestRequest
            scope.launch {
                val result = holder.setExposureCompensation(index)
                if (result is CameraKResult.Failure && result.error !is CameraKError.Busy) {
                    CameraKLogger.w(
                        TAG,
                        "Setting the exposure failed: ${result.error::class.simpleName}",
                        result.error.cause,
                    )
                }
                // From here the slider shows what the camera reports, not what was asked for.
                if (request == latestRequest) requested = null
            }
        },
        interactionSource = interactions,
        modifier = Modifier.offset {
            val reticleHalf = RETICLE_HALF.toPx()
            val gap = SLIDER_GAP.toPx()
            val width = SLIDER_WIDTH.toPx()
            val height = SLIDER_HEIGHT.toPx()
            val right = tap.x + reticleHalf + gap
            val x = if (right + width <= contentBounds.width) right else tap.x - reticleHalf - gap - width
            val y = (tap.y - height / 2).coerceIn(0f, (contentBounds.height - height).coerceAtLeast(0f))
            IntOffset(x.roundToInt(), y.roundToInt())
        },
    )
}

/**
 * Returns the camera to its own focus and exposure when the mode or the camera changes, on a camera
 * that supports either; the screen opening is no change.
 */
@Composable
internal fun ResetFocusOnSwitch(holder: CameraKStateHolder, mode: CameraMode) {
    val cameraId = holder.uiState.collectAsState().value.cameraId
    val last = remember(holder) { LastSwitch() }
    LaunchedEffect(holder, mode, cameraId) {
        val switched = (last.mode != null && last.mode != mode) ||
            (last.cameraId != null && cameraId != null && last.cameraId != cameraId)
        last.mode = mode
        if (cameraId != null) last.cameraId = cameraId
        if (switched) holder.resetFocusAndExposureIfSupported()
    }
}

/** The mode and camera [ResetFocusOnSwitch] last saw. */
private class LastSwitch {
    var mode: CameraMode? = null
    var cameraId: String? = null
}

/** `resetFocusAndExposure()` on a camera that has a focus point or exposure compensation; others are left alone. */
internal suspend fun CameraKStateHolder.resetFocusAndExposureIfSupported() {
    val cameraId = uiState.value.cameraId ?: return
    val offered = capabilities.value
    if (!offered.supportsFocusPoint(cameraId) && offered.exposureCompensationRange(cameraId) == null) return
    val result = resetFocusAndExposure()
    if (result is CameraKResult.Failure && result.error !is CameraKError.Unsupported) {
        CameraKLogger.w(
            TAG,
            "Resetting focus and exposure failed: ${result.error::class.simpleName}",
            result.error.cause,
        )
    }
}

private const val TAG = "Sample"
private val SLIDER_SHOWN_AFTER_TAP = 3.seconds

// The reticle is 72 dp; the slider, 28 dp wide and 156 dp tall with its thumb, sits 16 dp beside it.
private val RETICLE_HALF = 36.dp
private val SLIDER_GAP = 16.dp
private val SLIDER_WIDTH = 28.dp
private val SLIDER_HEIGHT = 156.dp

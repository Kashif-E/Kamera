package org.company.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.Flashlight
import com.composables.icons.lucide.FlashlightOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.SwitchCamera
import com.composables.icons.lucide.Zap
import com.composables.icons.lucide.ZapOff
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import org.company.app.designsystem.CameraTopBar
import org.company.app.designsystem.CaptureDock
import org.company.app.designsystem.CircleIconButton
import org.company.app.designsystem.CircleIconButtonDefaults
import org.company.app.designsystem.DetectionChip
import org.company.app.designsystem.ModeSwitcher
import org.company.app.designsystem.PhotoThumb
import org.company.app.designsystem.Pill
import org.company.app.designsystem.RecordingTimerPill
import org.company.app.designsystem.SampleTheme
import org.company.app.designsystem.ShutterButton
import org.company.app.designsystem.ShutterVariant
import org.company.app.designsystem.TopBarTitle
import org.company.app.designsystem.ZoomPills

/** The flash's button: off, auto, or on (indigo). Each tap moves to the next mode the camera has. */
@Composable
internal fun FlashButton(flashMode: FlashMode, onClick: () -> Unit, modifier: Modifier = Modifier) {
    CircleIconButton(
        icon = if (flashMode == FlashMode.OFF) Lucide.ZapOff else Lucide.Zap,
        contentDescription = "Flash: ${flashMode.name.lowercase()}",
        onClick = onClick,
        modifier = modifier,
        style = if (flashMode == FlashMode.ON) CircleIconButtonDefaults.active() else CircleIconButtonDefaults.style(),
    )
}

/** The torch's button, indigo while the torch is on. */
@Composable
internal fun TorchButton(torchMode: TorchMode, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val on = torchMode != TorchMode.OFF
    CircleIconButton(
        icon = if (on) Lucide.Flashlight else Lucide.FlashlightOff,
        contentDescription = if (on) "Torch: on" else "Torch: off",
        onClick = onClick,
        modifier = modifier,
        style = if (on) CircleIconButtonDefaults.active() else CircleIconButtonDefaults.style(),
    )
}

/** The way back from the QR, Text and Analyze modes to the photo camera. */
@Composable
internal fun BackToCameraButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    CircleIconButton(icon = Lucide.ChevronLeft, contentDescription = "Back to camera", onClick = onClick, modifier = modifier)
}

/**
 * The photo and video modes' top bar: flash and aspect ratio at the start, torch and settings at the
 * end, or, while recording, only the recorded time.
 */
@Composable
internal fun CaptureTopBar(
    flashMode: FlashMode,
    torchMode: TorchMode,
    aspectRatio: AspectRatio,
    isRecording: Boolean,
    recordingDurationMs: Long,
    onFlashToggle: () -> Unit,
    onTorchToggle: () -> Unit,
    onAspectRatioCycle: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isRecording) {
        CameraTopBar(modifier = modifier, center = { RecordingTimerPill(durationMs = recordingDurationMs) })
        return
    }
    val ratio = aspectRatio.label()
    CameraTopBar(
        modifier = modifier,
        leading = listOf(
            { FlashButton(flashMode = flashMode, onClick = onFlashToggle) },
            // The ratio is its own description, as the benchmarks find the chip by it.
            { Pill(text = ratio, onClick = onAspectRatioCycle, contentDescription = ratio) },
        ),
        trailing = listOf(
            { TorchButton(torchMode = torchMode, onClick = onTorchToggle) },
            { CircleIconButton(icon = Lucide.SlidersHorizontal, contentDescription = "Settings", onClick = onOpenSettings) },
        ),
    )
}

/** The QR, Text and Analyze modes' top bar: back, the mode's [title], and [trailing] at the end. */
@Composable
internal fun ToolTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: List<@Composable () -> Unit> = emptyList(),
) {
    CameraTopBar(
        modifier = modifier,
        leading = listOf({ BackToCameraButton(onClick = onBack) }, { TopBarTitle(text = title) }),
        trailing = trailing,
    )
}

/** What the capture controls show and do; the screen fills it from the holder. */
internal class CaptureControlsState(
    val mode: CameraMode,
    val cameraLive: Boolean,
    val isRecording: Boolean,
    val isPaused: Boolean,
    val canPause: Boolean,
    val isCapturing: Boolean,
    val zoomStops: List<Float>,
    val zoom: Float,
    val thumbnail: ImageBitmap?,
    val rotationDegrees: Float,
)

/**
 * The photo and video modes' controls: the zoom pills (a note while recording), the modes, and the
 * last photo or pause, the shutter, and flip. The shutter row shows once the camera is live, as the
 * benchmarks read Flip Camera's arrival as the camera being Ready.
 */
@Composable
internal fun CaptureControls(
    state: CaptureControlsState,
    onZoom: (Float) -> Unit,
    onModeChange: (CameraMode) -> Unit,
    onShutter: () -> Unit,
    onPauseToggle: () -> Unit,
    onFlip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val upright = Modifier.graphicsLayer { rotationZ = state.rotationDegrees }
    CaptureDock(
        modifier = modifier,
        above = {
            when {
                state.isRecording -> Text(
                    text = "Lens switching is paused while recording",
                    style = SampleTheme.typography.note,
                    color = SampleTheme.colors.inkSecondary,
                )
                state.cameraLive && state.zoomStops.size > 1 ->
                    ZoomPills(stops = state.zoomStops, zoom = state.zoom, onSelect = onZoom)
            }
        },
        modes = {
            ModeSwitcher(
                modes = CameraMode.entries,
                selected = state.mode,
                onSelect = onModeChange,
                label = { it.label },
                enabled = !state.isRecording,
            )
        },
        start = {
            when {
                !state.cameraLive -> Unit
                state.isRecording && state.canPause -> CircleIconButton(
                    icon = if (state.isPaused) Lucide.Play else Lucide.Pause,
                    contentDescription = if (state.isPaused) "Resume recording" else "Pause recording",
                    onClick = onPauseToggle,
                    modifier = upright,
                    style = CircleIconButtonDefaults.control(),
                )
                !state.isRecording -> PhotoThumb(image = state.thumbnail, modifier = upright)
            }
        },
        shutter = {
            if (state.cameraLive) {
                ShutterButton(
                    variant = when {
                        state.isRecording -> ShutterVariant.Recording
                        state.mode == CameraMode.Video -> ShutterVariant.Video
                        else -> ShutterVariant.Photo
                    },
                    onClick = onShutter,
                    modifier = upright,
                    enabled = !state.isCapturing,
                )
            }
        },
        end = {
            if (state.cameraLive) {
                CircleIconButton(
                    icon = Lucide.SwitchCamera,
                    // The benchmarks find this button by its description.
                    contentDescription = "Flip Camera",
                    onClick = onFlip,
                    modifier = upright,
                    enabled = !state.isRecording,
                    style = CircleIconButtonDefaults.control(),
                )
            }
        },
    )
}

/**
 * Over the photo and video preview: a chip for the newest code and one for the text in view, when the
 * scanner or the recognizer runs in these modes. Each opens its mode.
 */
@Composable
internal fun DetectionChips(
    latestCode: ScannedCode?,
    textLine: String?,
    onOpenQr: () -> Unit,
    onOpenText: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = SampleTheme.spacing.lg, vertical = SampleTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SampleTheme.spacing.sm),
    ) {
        if (latestCode != null) DetectionChip(label = latestCode.format, value = latestCode.value, onClick = onOpenQr)
        if (textLine != null) DetectionChip(label = "Text", value = textLine, onClick = onOpenText)
    }
}

/** The ratio as the Sample shows it, "4:3". */
internal fun AspectRatio.label(): String = name.removePrefix("RATIO_").replace('_', ':')

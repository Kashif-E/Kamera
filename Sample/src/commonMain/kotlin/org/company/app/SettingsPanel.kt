package org.company.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import org.company.app.designsystem.CircleIconButton
import org.company.app.designsystem.OptionRow
import org.company.app.designsystem.SampleTheme
import org.company.app.designsystem.Sheet
import org.company.app.designsystem.SheetDefaults
import org.company.app.designsystem.SheetHeader
import org.company.app.designsystem.SheetHeaderEmphasis
import org.company.app.designsystem.SwitchRow

/**
 * The settings, a sheet over the dimmed camera: the configuration's choices (locked while
 * [configLocked], as a change re-initializes the camera), the saver, the scanners in Photo and Video,
 * and the orientation lock. A tap on the dimmed camera closes it.
 */
@Composable
internal fun SettingsSheet(
    settings: CameraSettings,
    onSettingsChange: (CameraSettings) -> Unit,
    choices: CameraScreenChoices,
    onChoicesChange: (CameraScreenChoices) -> Unit,
    saveToGallery: Boolean,
    onSaveToGalleryChange: (Boolean) -> Unit,
    deviceOrientation: DeviceOrientation,
    configLocked: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val editable = !configLocked
    Box(modifier = modifier) {
        Box(
            Modifier
                .matchParentSize()
                .background(SampleTheme.colors.scrim)
                .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
        )
        Sheet(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(top = SampleTheme.sizes.topBar)
                .widthIn(max = SampleTheme.sizes.contentMaxWidth)
                .fillMaxWidth()
                // Taps on the sheet stay on it rather than reaching the dimmed camera behind.
                .pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState()),
            style = SheetDefaults.panel(),
        ) {
            SheetHeader(title = "Settings", emphasis = SheetHeaderEmphasis.Heading) {
                CircleIconButton(icon = Lucide.X, contentDescription = "Close Settings", onClick = onDismiss)
            }
            OptionRow(
                label = "Aspect ratio",
                options = sampleAspectRatios,
                selected = settings.aspectRatio,
                onSelect = { onSettingsChange(settings.copy(aspectRatio = it)) },
                optionLabel = { it.label() },
                enabled = editable,
            )
            OptionRow(
                label = "Resolution",
                options = RESOLUTIONS,
                selected = settings.resolution,
                onSelect = { onSettingsChange(settings.copy(resolution = it)) },
                optionLabel = ::resolutionLabel,
                enabled = editable,
            )
            OptionRow(
                label = "Image format",
                // HEIC stays out until the platform capture slices implement it; until then a HEIC
                // capture returns an error.
                options = listOf(ImageFormat.JPEG, ImageFormat.PNG),
                selected = settings.imageFormat,
                onSelect = { onSettingsChange(settings.copy(imageFormat = it)) },
                optionLabel = { it.name },
                enabled = editable,
            )
            OptionRow(
                label = "Quality priority",
                options = QUALITY_PRIORITIES,
                selected = settings.qualityPrioritization,
                onSelect = { onSettingsChange(settings.copy(qualityPrioritization = it)) },
                optionLabel = ::qualityLabel,
                enabled = editable,
            )
            OptionRow(
                label = "Camera type",
                options = CAMERA_TYPES,
                selected = settings.cameraDeviceType,
                onSelect = { onSettingsChange(settings.copy(cameraDeviceType = it)) },
                optionLabel = ::cameraTypeLabel,
                enabled = editable,
            )
            SwitchRow(
                title = "Save to gallery",
                checked = saveToGallery,
                onCheckedChange = onSaveToGalleryChange,
                subtitle = "Asks for photo library access the first time",
            )
            SwitchRow(
                title = "QR Scanner",
                checked = choices.qrScanning,
                onCheckedChange = { onChoicesChange(choices.copy(qrScanning = it)) },
                subtitle = "Also reads codes in Photo and Video",
            )
            SwitchRow(
                title = "Text Recognition",
                checked = choices.ocr,
                onCheckedChange = { onChoicesChange(choices.copy(ocr = it)) },
                subtitle = "Also reads text in Photo and Video",
            )
            OptionRow(
                label = "Orientation lock · now ${orientationLabel(deviceOrientation)}",
                options = listOf<DeviceOrientation?>(null) + DeviceOrientation.entries,
                selected = choices.lockedOrientation,
                onSelect = { onChoicesChange(choices.copy(lockedOrientation = it)) },
                optionLabel = ::orientationLabel,
            )
        }
    }
}

private val RESOLUTIONS = listOf(null, 1920 to 1080, 1280 to 720, 640 to 480)

private val QUALITY_PRIORITIES = listOf(
    QualityPrioritization.SPEED,
    QualityPrioritization.BALANCED,
    QualityPrioritization.QUALITY,
    QualityPrioritization.NONE,
)

private val CAMERA_TYPES = listOf(
    CameraDeviceType.WIDE_ANGLE,
    CameraDeviceType.ULTRA_WIDE,
    CameraDeviceType.TELEPHOTO,
    CameraDeviceType.MACRO,
    CameraDeviceType.DEFAULT,
)

// 1920×1080 reads "1080p".
private fun resolutionLabel(resolution: Pair<Int, Int>?): String = resolution?.let { "${it.second}p" } ?: "Auto"

private fun qualityLabel(priority: QualityPrioritization): String = when (priority) {
    QualityPrioritization.SPEED -> "Speed"
    QualityPrioritization.BALANCED -> "Balanced"
    QualityPrioritization.QUALITY -> "Quality"
    QualityPrioritization.NONE -> "None"
}

private fun cameraTypeLabel(type: CameraDeviceType): String = when (type) {
    CameraDeviceType.WIDE_ANGLE -> "Wide"
    CameraDeviceType.ULTRA_WIDE -> "Ultra wide"
    CameraDeviceType.TELEPHOTO -> "Telephoto"
    CameraDeviceType.MACRO -> "Macro"
    CameraDeviceType.DEFAULT -> "Default"
}

private fun orientationLabel(orientation: DeviceOrientation?): String = when (orientation) {
    null -> "Auto"
    DeviceOrientation.PORTRAIT -> "Portrait"
    DeviceOrientation.LANDSCAPE_LEFT -> "Landscape L"
    DeviceOrientation.PORTRAIT_UPSIDE_DOWN -> "Upside down"
    DeviceOrientation.LANDSCAPE_RIGHT -> "Landscape R"
}

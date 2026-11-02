package org.company.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.AlignVerticalSpaceAround
import com.composables.icons.lucide.Aperture
import com.composables.icons.lucide.Focus
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Monitor
import com.composables.icons.lucide.ScanLine
import com.composables.icons.lucide.Type
import com.composables.icons.lucide.X
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization

private val Surface = Color(0xFF1A1A1A)

@Composable
internal fun SettingsPanel(
    modifier: Modifier = Modifier,
    resolution: Pair<Int, Int>?,
    imageFormat: ImageFormat,
    qualityPrioritization: QualityPrioritization,
    cameraDeviceType: CameraDeviceType,
    isQRScanningEnabled: Boolean,
    isOCREnabled: Boolean,
    lockedOrientation: DeviceOrientation?,
    deviceOrientation: DeviceOrientation,
    onResolutionChange: (Pair<Int, Int>?) -> Unit,
    onImageFormatChange: (ImageFormat) -> Unit,
    onQualityPrioritizationChange: (QualityPrioritization) -> Unit,
    onCameraDeviceTypeChange: (CameraDeviceType) -> Unit,
    onQRScanningToggle: (Boolean) -> Unit,
    onOCRToggle: (Boolean) -> Unit,
    onOrientationLockChange: (DeviceOrientation?) -> Unit,
    onDismiss: () -> Unit,
) {
    val resolutionOptions = listOf(null, 1920 to 1080, 1280 to 720, 640 to 480)
    fun resLabel(r: Pair<Int, Int>?): String = r?.let { "${it.first}x${it.second}" } ?: "Auto"

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Lucide.X,
                        contentDescription = "Close",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // Resolution
            SettingRow(icon = Lucide.Focus, label = "Resolution") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(resolutionOptions) { option ->
                        Chip(resLabel(option), option == resolution) { onResolutionChange(option) }
                    }
                }
            }

            // Image Format
            SettingRow(icon = Lucide.Image, label = "Image Format") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ImageFormat.entries.toList()) { format ->
                        Chip(format.name, format == imageFormat) { onImageFormatChange(format) }
                    }
                }
            }

            // Quality Prioritization
            SettingRow(icon = Lucide.Aperture, label = "Quality Priority") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(QualityPrioritization.entries.toList()) { priority ->
                        Chip(priority.name, priority == qualityPrioritization) {
                            onQualityPrioritizationChange(priority)
                        }
                    }
                }
            }

            // Camera Device Type
            SettingRow(icon = Lucide.Monitor, label = "Camera Type") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(CameraDeviceType.entries.toList()) { type ->
                        Chip(
                            label = type.name.replace("_", " "),
                            selected = type == cameraDeviceType,
                            onClick = { onCameraDeviceTypeChange(type) },
                        )
                    }
                }
            }

            // Plugin toggles
            SettingToggle(Lucide.ScanLine, "QR Scanner", isQRScanningEnabled, onQRScanningToggle)
            SettingToggle(Lucide.Type, "OCR (Text Recognition)", isOCREnabled, onOCRToggle)

            // Orientation lock
            SettingRow(icon = Lucide.AlignVerticalSpaceAround, label = "Orientation Lock (${deviceOrientation.name})") {
                val options = listOf<DeviceOrientation?>(null) + DeviceOrientation.entries
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(options) { option ->
                        Chip(
                            label = option?.name?.replace("_", " ") ?: "AUTO",
                            selected = option == lockedOrientation,
                            onClick = { onOrientationLockChange(option) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = TextSecondary,
            )
        }
        content()
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) Color.White else ChipBg,
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) Color.Black else TextSecondary,
    )

    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = bgColor,
        shape = RoundedCornerShape(20.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            color = textColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SettingToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) Color(0xFF4285F4) else TextSecondary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF4285F4),
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = ChipBg,
            ),
        )
    }
}

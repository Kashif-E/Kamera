package org.company.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Ratio
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Sun
import com.composables.icons.lucide.SwitchCamera
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Zap
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode

@Composable
internal fun TopBar(
    modifier: Modifier = Modifier,
    flashMode: FlashMode,
    torchMode: TorchMode,
    aspectRatio: AspectRatio,
    isRecording: Boolean,
    recordingDurationMs: Long,
    onFlashToggle: () -> Unit,
    onTorchToggle: () -> Unit,
    onAspectRatioCycle: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isRecording) {
            RecordingTimerHud(durationMs = recordingDurationMs)
        } else {
            TopBarIconChip(
                icon = if (flashMode != FlashMode.OFF) Lucide.Zap else Lucide.Zap,
                label = flashMode.name,
                active = flashMode != FlashMode.OFF,
                onClick = onFlashToggle,
            )

            Spacer(modifier = Modifier.width(6.dp))

            TopBarIconChip(
                icon = Lucide.Ratio,
                label = aspectRatio.name.replace("RATIO_", "").replace("_", ":"),
                active = false,
                onClick = onAspectRatioCycle,
            )

            Spacer(modifier = Modifier.weight(1f))

            TopBarIconChip(
                icon = if (torchMode != TorchMode.OFF) Lucide.Sun else Lucide.Sun,
                label = torchMode.name,
                active = torchMode != TorchMode.OFF,
                onClick = onTorchToggle,
            )
        }
    }
}

@Composable
private fun TopBarIconChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = if (active) Color.White.copy(alpha = 0.2f) else Color.Transparent,
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) Color.Yellow else TextSecondary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                color = if (active) Color.Yellow else TextSecondary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
internal fun ZoomChips(modifier: Modifier = Modifier, zoomLevel: Float, maxZoom: Float, onZoomChange: (Float) -> Unit) {
    val stops = buildList {
        add(1f)
        if (maxZoom >= 2f) add(2f)
        if (maxZoom > 2f && maxZoom >= 4f) add(4f.coerceAtMost(maxZoom))
        if (maxZoom > 4f) add(maxZoom)
    }.distinct()

    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.5f),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            stops.forEach { stop ->
                val isActive = (zoomLevel - stop).let { it > -0.15f && it < 0.15f }
                val label = if (stop == stop.toLong().toFloat()) {
                    "${stop.toInt()}x"
                } else {
                    "${(stop * 10).toInt() / 10f}x"
                }
                Surface(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { onZoomChange(stop) },
                    color = if (isActive) Color.White.copy(alpha = 0.25f) else Color.Transparent,
                    shape = CircleShape,
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = label,
                            color = if (isActive) Color.Yellow else TextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ModeSwitcher(currentMode: CameraMode, onModeChange: (CameraMode) -> Unit, enabled: Boolean = true) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CameraMode.entries.forEach { mode ->
            val isSelected = mode == currentMode
            val textColor by animateColorAsState(
                targetValue = if (isSelected) TextPrimary else TextSecondary,
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(enabled = enabled) { onModeChange(mode) },
            ) {
                Text(
                    text = mode.name.uppercase(),
                    color = textColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    letterSpacing = 1.sp,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .height(3.dp)
                        .width(if (isSelected) 24.dp else 0.dp)
                        .background(
                            if (isSelected) TextPrimary else Color.Transparent,
                            RoundedCornerShape(1.5.dp),
                        ),
                )
            }
        }
    }
}

@Composable
internal fun ShutterButton(
    mode: CameraMode,
    isRecording: Boolean,
    isCapturing: Boolean,
    onPhotoCapture: () -> Unit,
    onVideoToggle: () -> Unit,
) {
    val isVideoMode = mode == CameraMode.Video

    val outerRingColor by animateColorAsState(
        targetValue = if (isRecording) Red else Color.White,
    )
    val innerColor by animateColorAsState(
        targetValue = if (isVideoMode) Red else Color.White,
    )
    val innerSize by animateDpAsState(
        targetValue = if (isRecording) 24.dp else 60.dp,
    )
    val innerCornerRadius by animateDpAsState(
        targetValue = if (isRecording) 6.dp else 30.dp,
    )

    val pulseScale = if (isRecording) {
        val infiniteTransition = rememberInfiniteTransition()
        val scale by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween<Float>(1000, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseScale",
        )
        scale
    } else {
        1.0f
    }

    val pressScale by animateFloatAsState(
        targetValue = if (isCapturing) 0.85f else 1.0f,
    )

    val combinedScale = pulseScale * pressScale

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(72.dp)
            .clickable(
                enabled = !isCapturing,
                onClick = if (isVideoMode) onVideoToggle else onPhotoCapture,
            ),
    ) {
        Box(
            modifier = Modifier
                .size((72 * combinedScale).dp)
                .border(3.dp, outerRingColor, CircleShape),
        )
        Box(
            modifier = Modifier
                .size(innerSize * combinedScale)
                .background(innerColor, RoundedCornerShape(innerCornerRadius)),
        )
    }
}

@Composable
internal fun CameraFlipButton(onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .background(ChipBg, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(
            imageVector = Lucide.SwitchCamera,
            contentDescription = "Flip Camera",
            tint = TextPrimary,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
internal fun SettingsButton(isOpen: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .background(
                if (isOpen) ChipBgSelected else ChipBg,
                CircleShape,
            )
            .clickable(onClick = onClick),
    ) {
        Icon(
            imageVector = if (isOpen) Lucide.X else Lucide.Settings,
            contentDescription = if (isOpen) "Close Settings" else "Settings",
            tint = TextPrimary,
            modifier = Modifier.size(22.dp),
        )
    }
}

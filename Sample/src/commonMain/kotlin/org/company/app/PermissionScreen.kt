package org.company.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Lucide
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import org.company.app.designsystem.BulletRow
import org.company.app.designsystem.ButtonSize
import org.company.app.designsystem.IconBadge
import org.company.app.designsystem.PrimaryButton
import org.company.app.designsystem.SampleTheme
import org.company.app.designsystem.SecondaryButton

/**
 * What the Sample shows while the camera is not granted: why it needs the camera, what else it may
 * ask for, Allow camera access, and, for a permanently denied camera, Open Settings when
 * [onOpenSettings] can open them.
 */
@Composable
internal fun PermissionScreen(
    cameraStatus: PermissionStatus?,
    onAllow: () -> Unit,
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val spacing = SampleTheme.spacing
    val colors = SampleTheme.colors
    Column(
        modifier = modifier.background(colors.background).padding(
            start = spacing.xxxl,
            end = spacing.xxxl,
            bottom = spacing.controlsBottom - spacing.sm,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.weight(1f).widthIn(max = SampleTheme.sizes.contentMaxWidth),
            verticalArrangement = Arrangement.spacedBy(spacing.lg + spacing.xxs, Alignment.CenterVertically),
        ) {
            IconBadge(icon = Lucide.Camera)
            Text(text = "Camera access is needed", style = SampleTheme.typography.display, color = colors.ink)
            Text(
                text = "The sample shows CameraK's preview, capture, recording and scanning. It needs the camera " +
                    "to show any of them. Nothing leaves your device.",
                style = SampleTheme.typography.bodyLarge,
                color = colors.inkBody,
            )
            Column(
                modifier = Modifier.padding(top = spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                BulletRow(text = "Camera, always", accent = true)
                BulletRow(text = "Microphone, only when you record with sound")
                BulletRow(text = "Photo library, only when Save to gallery is on")
            }
        }
        Column(
            modifier = Modifier.widthIn(max = SampleTheme.sizes.contentMaxWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            PrimaryButton(
                text = "Allow camera access",
                onClick = onAllow,
                modifier = Modifier.fillMaxWidth(),
                size = ButtonSize.Large,
            )
            // Only the settings can grant a permanently denied camera; any other status still prompts.
            if (onOpenSettings != null && cameraStatus == PermissionStatus.PermanentlyDenied) {
                SecondaryButton(text = "Open Settings", onClick = onOpenSettings, modifier = Modifier.fillMaxWidth())
            }
            val camera = cameraStatus ?: PermissionStatus.NotDetermined
            val footnote = permissionNotice(mapOf(CameraKPermission.CAMERA to camera))
            if (footnote != null) {
                Text(
                    text = footnote,
                    style = SampleTheme.typography.caption,
                    color = colors.inkSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Opens this app's page in the system settings, where a refused permission can be granted; null where
 * the platform has none.
 */
@Composable
internal expect fun rememberAppSettingsOpener(): (() -> Unit)?

package org.company.app

import androidx.compose.runtime.Composable
import com.kashif.cameraK.utils.CameraKLogger
import java.awt.Desktop
import java.io.IOException
import java.net.URI

// Only macOS asks for the camera; its Privacy & Security pane is where a refusal is undone.
@Composable
internal actual fun rememberAppSettingsOpener(): (() -> Unit)? {
    val macOs = System.getProperty("os.name").orEmpty().startsWith("Mac")
    if (!macOs || !Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) return null
    return ::openCameraPrivacySettings
}

private fun openCameraPrivacySettings() {
    try {
        Desktop.getDesktop().browse(URI("x-apple.systempreferences:com.apple.preference.security?Privacy_Camera"))
    } catch (e: IOException) {
        CameraKLogger.w("Sample", "Opening the camera privacy settings failed", e)
    }
}

package org.company.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.kashif.cameraK.utils.CameraKLogger

@Composable
internal actual fun rememberAppSettingsOpener(): (() -> Unit)? {
    val context = LocalContext.current
    return remember(context) {
        {
            val appPage = Uri.fromParts("package", context.packageName, null)
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appPage).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                CameraKLogger.w("Sample", "Opening the app's settings failed", e)
            }
        }
    }
}

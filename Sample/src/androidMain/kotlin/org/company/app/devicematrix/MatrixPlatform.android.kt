package org.company.app.devicematrix

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.mediasaverplugin.MediaSaverConfig
import com.kashif.mediasaverplugin.MediaSaverPlugin
import java.io.File

@Composable
internal actual fun rememberMatrixPlatform(permission: (CameraKPermission) -> PermissionStatus?): MatrixPlatform {
    val context = LocalContext.current.applicationContext
    return remember(context) { AndroidMatrixPlatform(context, permission) }
}

// The activity sets FLAG_KEEP_SCREEN_ON when it is started for the matrix.
@Composable
internal actual fun KeepScreenOn() = Unit

private class AndroidMatrixPlatform(
    context: Context,
    private val permissions: (CameraKPermission) -> PermissionStatus?,
) : MatrixPlatform {
    private val platformContext = CameraKPlatformContext(context)
    override val platformName = "android"
    override val deviceDescription =
        "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} " +
            "(API ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}"
    override val reportDirectory: String = File(context.filesDir, "camerak-matrix").path
    override val files: MatrixFiles = platformFiles

    override fun newHolder(config: CameraConfiguration, plugins: List<CameraKPlugin>): CameraKStateHolder =
        CameraKStateHolder(config, platformContext, plugins)

    override fun newMediaSaver(): MediaSaverPlugin = MediaSaverPlugin(
        MediaSaverConfig {
            autoSave = false
        },
        platformContext,
    )

    override fun permission(permission: CameraKPermission): PermissionStatus? = permissions(permission)

    override fun log(line: String) {
        Log.i("CameraKMatrix", line)
    }
}

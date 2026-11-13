package org.company.app.devicematrix

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
internal actual fun rememberMatrixPlatform(permission: (CameraKPermission) -> PermissionStatus?): MatrixPlatform =
    remember { DesktopMatrixPlatform(permission) }

@Composable
internal actual fun KeepScreenOn() = Unit

private class DesktopMatrixPlatform(private val permissions: (CameraKPermission) -> PermissionStatus?) :
    MatrixPlatform {
    private val platformContext = CameraKPlatformContext()
    override val platformName = "desktop"
    override val deviceDescription = "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
    override val reportDirectory: String = File(System.getProperty("user.home"), "camerak-matrix").path
    override val files: MatrixFiles = platformFiles
    override val promptsOnOpen = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

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
        println("CameraKMatrix: $line")
    }
}

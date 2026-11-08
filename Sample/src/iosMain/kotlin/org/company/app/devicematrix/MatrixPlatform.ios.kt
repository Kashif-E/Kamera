@file:OptIn(ExperimentalForeignApi::class)

package org.company.app.devicematrix

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.mediasaverplugin.MediaSaverConfig
import com.kashif.mediasaverplugin.MediaSaverPlugin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSLog
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.posix.uname
import platform.posix.utsname

@Composable
internal actual fun rememberMatrixPlatform(permission: (CameraKPermission) -> PermissionStatus?): MatrixPlatform =
    remember { IosMatrixPlatform(permission) }

@Composable
internal actual fun KeepScreenOn() {
    DisposableEffect(Unit) {
        UIApplication.sharedApplication.idleTimerDisabled = true
        onDispose { UIApplication.sharedApplication.idleTimerDisabled = false }
    }
}

private class IosMatrixPlatform(private val permissions: (CameraKPermission) -> PermissionStatus?) : MatrixPlatform {
    private val platformContext = CameraKPlatformContext()
    override val platformName = "ios"
    override val deviceDescription: String = UIDevice.currentDevice.let {
        "${it.model} ${machine()}, ${it.systemName} ${it.systemVersion}"
    }
    override val reportDirectory: String =
        (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String) +
            "/camerak-matrix"
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
        NSLog("CameraKMatrix: " + line.replace("%", "%%"))
    }
}

// The hardware model, such as iPhone15,2.
private fun machine(): String = memScoped {
    val name = alloc<utsname>()
    uname(name.ptr)
    name.machine.toKString()
}

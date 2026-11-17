package dev.kashifmehmood.camerak.mediasaver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.kashifmehmood.camerak.permissions.CameraKPermission
import dev.kashifmehmood.camerak.permissions.platformPermissionStatus
import dev.kashifmehmood.camerak.platform.CameraKPlatformContext
import dev.kashifmehmood.camerak.plugin.androidApplicationContext
import dev.kashifmehmood.camerak.utils.InternalCameraKApi

@OptIn(InternalCameraKApi::class)
internal actual fun platformGalleryStore(context: CameraKPlatformContext, config: MediaSaverConfig): GalleryStore =
    AndroidGalleryStore(context.androidApplicationContext, config)

@OptIn(InternalCameraKApi::class)
internal actual fun platformGalleryPermissionRead(context: CameraKPlatformContext): GalleryPermissionRead =
    GalleryPermissionRead { platformPermissionStatus(context, CameraKPermission.GALLERY_ADD) }

@Composable
internal actual fun rememberCameraKPlatformContext(): CameraKPlatformContext {
    val context = LocalContext.current
    return remember(context) { CameraKPlatformContext(context) }
}

package com.kashif.imagesaverplugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.platformPermissionStatus
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.plugin.androidApplicationContext
import com.kashif.cameraK.utils.InternalCameraKApi

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

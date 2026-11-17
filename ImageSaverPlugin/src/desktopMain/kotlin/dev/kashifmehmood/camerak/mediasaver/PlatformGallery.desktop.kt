package dev.kashifmehmood.camerak.mediasaver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.kashifmehmood.camerak.permissions.PermissionStatus
import dev.kashifmehmood.camerak.platform.CameraKPlatformContext

internal actual fun platformGalleryStore(context: CameraKPlatformContext, config: MediaSaverConfig): GalleryStore =
    DesktopGalleryStore(config)

// The desktop store needs no permission, so the saver never reads this.
internal actual fun platformGalleryPermissionRead(context: CameraKPlatformContext): GalleryPermissionRead =
    GalleryPermissionRead { PermissionStatus.Granted }

@Composable
internal actual fun rememberCameraKPlatformContext(): CameraKPlatformContext = remember { CameraKPlatformContext() }

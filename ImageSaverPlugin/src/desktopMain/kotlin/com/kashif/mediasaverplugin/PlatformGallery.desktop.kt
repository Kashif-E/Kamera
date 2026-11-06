package com.kashif.mediasaverplugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.platform.CameraKPlatformContext

internal actual fun platformGalleryStore(context: CameraKPlatformContext, config: MediaSaverConfig): GalleryStore =
    DesktopGalleryStore(config)

// The desktop store needs no permission, so the saver never reads this.
internal actual fun platformGalleryPermissionRead(context: CameraKPlatformContext): GalleryPermissionRead =
    GalleryPermissionRead { PermissionStatus.Granted }

@Composable
internal actual fun rememberCameraKPlatformContext(): CameraKPlatformContext = remember { CameraKPlatformContext() }

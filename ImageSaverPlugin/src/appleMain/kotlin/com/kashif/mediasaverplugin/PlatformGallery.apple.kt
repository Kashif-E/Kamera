package com.kashif.mediasaverplugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.platformPermissionStatus
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.utils.InternalCameraKApi

internal actual fun platformGalleryStore(context: CameraKPlatformContext, config: MediaSaverConfig): GalleryStore =
    AppleGalleryStore()

@OptIn(InternalCameraKApi::class)
internal actual fun platformGalleryPermissionRead(context: CameraKPlatformContext): GalleryPermissionRead =
    GalleryPermissionRead { platformPermissionStatus(context, CameraKPermission.GALLERY_ADD) }

@Composable
internal actual fun rememberCameraKPlatformContext(): CameraKPlatformContext = remember { CameraKPlatformContext() }

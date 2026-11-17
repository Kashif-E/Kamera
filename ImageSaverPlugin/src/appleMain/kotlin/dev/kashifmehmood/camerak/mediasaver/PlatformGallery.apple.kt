package dev.kashifmehmood.camerak.mediasaver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.kashifmehmood.camerak.permissions.CameraKPermission
import dev.kashifmehmood.camerak.permissions.platformPermissionStatus
import dev.kashifmehmood.camerak.platform.CameraKPlatformContext
import dev.kashifmehmood.camerak.utils.InternalCameraKApi

internal actual fun platformGalleryStore(context: CameraKPlatformContext, config: MediaSaverConfig): GalleryStore =
    AppleGalleryStore()

@OptIn(InternalCameraKApi::class)
internal actual fun platformGalleryPermissionRead(context: CameraKPlatformContext): GalleryPermissionRead =
    GalleryPermissionRead { platformPermissionStatus(context, CameraKPermission.GALLERY_ADD) }

@Composable
internal actual fun rememberCameraKPlatformContext(): CameraKPlatformContext = remember { CameraKPlatformContext() }

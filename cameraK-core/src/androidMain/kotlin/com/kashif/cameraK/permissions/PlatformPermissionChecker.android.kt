package com.kashif.cameraK.permissions

import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.utils.InternalCameraKApi

/** Until the platform checker lands, every permission reads as Granted, as 1.x never gated on one. */
internal actual fun platformPermissionChecker(context: CameraKPlatformContext): CameraKPermissionChecker =
    CameraKPermissionChecker { PermissionStatus.Granted }

@InternalCameraKApi
public fun platformPermissionStatus(context: CameraKPlatformContext, permission: CameraKPermission): PermissionStatus =
    TODO()

@InternalCameraKApi
public fun androidPermissionName(permission: CameraKPermission): String? = TODO()

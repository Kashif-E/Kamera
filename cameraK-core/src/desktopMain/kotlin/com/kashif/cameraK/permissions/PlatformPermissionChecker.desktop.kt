package com.kashif.cameraK.permissions

import com.kashif.cameraK.platform.CameraKPlatformContext

/** Until the platform checker lands, every permission reads as Granted, as 1.x never gated on one. */
internal actual fun platformPermissionChecker(context: CameraKPlatformContext): CameraKPermissionChecker =
    CameraKPermissionChecker { PermissionStatus.Granted }

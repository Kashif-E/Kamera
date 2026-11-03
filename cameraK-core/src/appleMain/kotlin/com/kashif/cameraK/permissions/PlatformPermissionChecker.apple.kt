package com.kashif.cameraK.permissions

import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.utils.InternalCameraKApi
import platform.AVFoundation.AVAuthorizationStatus
import platform.AVFoundation.AVMediaType
import platform.Photos.PHAccessLevel
import platform.Photos.PHAuthorizationStatus

/** Until the platform checker lands, every permission reads as Granted, as 1.x never gated on one. */
internal actual fun platformPermissionChecker(context: CameraKPlatformContext): CameraKPermissionChecker =
    CameraKPermissionChecker { PermissionStatus.Granted }

internal interface ApplePermissionPlatform {
    fun captureStatus(mediaType: AVMediaType?): AVAuthorizationStatus

    fun photoStatus(accessLevel: PHAccessLevel): PHAuthorizationStatus

    fun requestCapture(mediaType: AVMediaType?, completion: (granted: Boolean) -> Unit)

    fun requestPhoto(accessLevel: PHAccessLevel, completion: (PHAuthorizationStatus) -> Unit)
}

private object SystemPermissionPlatform : ApplePermissionPlatform {
    override fun captureStatus(mediaType: AVMediaType?): AVAuthorizationStatus = TODO()

    override fun photoStatus(accessLevel: PHAccessLevel): PHAuthorizationStatus = TODO()

    override fun requestCapture(mediaType: AVMediaType?, completion: (granted: Boolean) -> Unit): Unit = TODO()

    override fun requestPhoto(accessLevel: PHAccessLevel, completion: (PHAuthorizationStatus) -> Unit): Unit = TODO()
}

internal var applePermissionPlatform: ApplePermissionPlatform = SystemPermissionPlatform

@InternalCameraKApi
public fun platformPermissionStatus(context: CameraKPlatformContext, permission: CameraKPermission): PermissionStatus =
    TODO()

@InternalCameraKApi
public fun requestPlatformPermissions(
    context: CameraKPlatformContext,
    permissions: List<CameraKPermission>,
    onResult: (Map<CameraKPermission, PermissionStatus>) -> Unit,
): Unit = TODO()

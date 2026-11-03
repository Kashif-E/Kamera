package com.kashif.cameraK.permissions

import com.kashif.cameraK.controller.CameraAuthorization
import com.kashif.cameraK.controller.MacCameraAuthorization
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.utils.InternalCameraKApi

/** Until the platform checker lands, every permission reads as Granted, as 1.x never gated on one. */
internal actual fun platformPermissionChecker(context: CameraKPlatformContext): CameraKPermissionChecker =
    CameraKPermissionChecker { PermissionStatus.Granted }

internal fun interface MacAuthorizationRead {
    fun status(permission: CameraKPermission): PermissionStatus
}

internal class JnaMacAuthorizationRead(private val camera: CameraAuthorization = MacCameraAuthorization()) :
    MacAuthorizationRead {
    override fun status(permission: CameraKPermission): PermissionStatus = TODO()
}

internal class DesktopPermissionChecker(private val osName: String, private val macRead: MacAuthorizationRead) :
    CameraKPermissionChecker {
    override fun status(permission: CameraKPermission): PermissionStatus = TODO()

    override fun allowsOpen(status: PermissionStatus): Boolean = TODO()
}

@Volatile
internal var desktopPermissionChecker: DesktopPermissionChecker =
    DesktopPermissionChecker(System.getProperty("os.name").orEmpty(), JnaMacAuthorizationRead())

@InternalCameraKApi
public fun platformPermissionStatus(context: CameraKPlatformContext, permission: CameraKPermission): PermissionStatus =
    TODO()

@InternalCameraKApi
public fun requestPlatformPermissions(
    context: CameraKPlatformContext,
    permissions: List<CameraKPermission>,
    onResult: (Map<CameraKPermission, PermissionStatus>) -> Unit,
): Unit = TODO()

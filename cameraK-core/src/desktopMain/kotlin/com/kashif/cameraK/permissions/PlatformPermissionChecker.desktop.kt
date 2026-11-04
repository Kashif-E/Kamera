package com.kashif.cameraK.permissions

import com.kashif.cameraK.controller.MacCameraAuthorization
import com.kashif.cameraK.controller.MacMediaType
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.utils.InternalCameraKApi
import java.awt.EventQueue

internal actual fun platformPermissionChecker(context: CameraKPlatformContext): CameraKPermissionChecker =
    desktopPermissionChecker

/** macOS's authorization of one permission. Tests fake this, never the Objective-C runtime under it. */
internal fun interface MacAuthorizationRead {
    fun status(permission: CameraKPermission): PermissionStatus
}

/**
 * macOS's authorization read over D1''s JNA seam, which owns the status mapping and the JNA-absent
 * rule. [read] is the status of one AVFoundation media type, [MacCameraAuthorization.status] in
 * production.
 */
internal class JnaMacAuthorizationRead(
    private val read: (MacMediaType) -> PermissionStatus = MacCameraAuthorization()::status,
) : MacAuthorizationRead {
    override fun status(permission: CameraKPermission): PermissionStatus =
        macMediaTypeOf(permission)?.let(read) ?: PermissionStatus.Granted
}

/** The AVFoundation media type whose authorization is [permission]'s on macOS; null for the gallery, a folder. */
internal fun macMediaTypeOf(permission: CameraKPermission): MacMediaType? = when (permission) {
    CameraKPermission.CAMERA -> MacMediaType.VIDEO
    CameraKPermission.MICROPHONE -> MacMediaType.AUDIO
    CameraKPermission.GALLERY_ADD -> null
}

/**
 * The desktop checker (Decision 15). Linux and Windows have no camera permission, so every status is
 * Granted. macOS reads its authorization through [macRead], except the gallery, which is a folder.
 * The camera may open at NotDetermined, because AVFoundation shows macOS's prompt when it opens.
 */
internal class DesktopPermissionChecker(osName: String, private val macRead: MacAuthorizationRead) :
    CameraKPermissionChecker {
    private val isMac = osName.lowercase().contains("mac")

    override fun status(permission: CameraKPermission): PermissionStatus = if (isMac &&
        permission != CameraKPermission.GALLERY_ADD
    ) {
        macRead.status(permission)
    } else {
        PermissionStatus.Granted
    }

    override fun allowsOpen(status: PermissionStatus): Boolean =
        status == PermissionStatus.Granted || status == PermissionStatus.NotDetermined
}

// The one desktop checker; tests replace it with one over a fake OS name and authorization read.
@Volatile
internal var desktopPermissionChecker: DesktopPermissionChecker =
    DesktopPermissionChecker(System.getProperty("os.name").orEmpty(), JnaMacAuthorizationRead())

/**
 * The desktop status of [permission]: Granted on Linux and Windows, macOS's authorization on macOS.
 * The holder's checker and `:cameraK`'s permission state both read this.
 */
@InternalCameraKApi
public fun platformPermissionStatus(context: CameraKPlatformContext, permission: CameraKPermission): PermissionStatus =
    desktopPermissionChecker.status(permission)

/**
 * Prompts for nothing: it re-reads the statuses of [permissions] and calls [onResult] with them on
 * the AWT event thread, Compose Desktop's Main. Linux and Windows have no permission to ask for.
 * macOS shows its camera prompt when the camera opens, and asking from the JVM would need an
 * Objective-C block, which plain JNA cannot build. A macOS PermanentlyDenied can only be changed in
 * System Settings, so the app has to send the user there.
 */
@InternalCameraKApi
public fun requestPlatformPermissions(
    context: CameraKPlatformContext,
    permissions: List<CameraKPermission>,
    onResult: (Map<CameraKPermission, PermissionStatus>) -> Unit,
) {
    EventQueue.invokeLater { onResult(permissions.associateWith { platformPermissionStatus(context, it) }) }
}

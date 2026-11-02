package com.kashif.cameraK.permissions

/** Reads permission statuses. The holder checks [CameraKPermission.CAMERA] through it before opening. */
public fun interface CameraKPermissionChecker {
    public fun status(permission: CameraKPermission): PermissionStatus

    /**
     * Whether the holder may open the camera at [status]. Granted everywhere; the desktop checker also
     * allows NotDetermined, because macOS prompts on open.
     */
    public fun allowsOpen(status: PermissionStatus): Boolean = status == PermissionStatus.Granted
}

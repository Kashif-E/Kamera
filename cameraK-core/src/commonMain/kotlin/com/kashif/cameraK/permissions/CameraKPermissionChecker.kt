package com.kashif.cameraK.permissions

/** Reads permission statuses. The holder checks [CameraKPermission.CAMERA] through it before opening. */
public fun interface CameraKPermissionChecker {
    public fun status(permission: CameraKPermission): PermissionStatus

    /**
     * Whether the holder may open the camera, or start a recording with audio, at [status]: the
     * camera's status for the one, the microphone's for the other. Granted everywhere; the desktop
     * checker also allows NotDetermined, because macOS prompts when the camera or the microphone opens.
     */
    public fun allowsOpen(status: PermissionStatus): Boolean = status == PermissionStatus.Granted
}

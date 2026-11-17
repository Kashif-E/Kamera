package dev.kashifmehmood.camerak.permissions

/** Reads permission statuses. The holder checks [CameraKPermission.CAMERA] through it before opening. */
public fun interface CameraKPermissionChecker {
    public fun status(permission: CameraKPermission): PermissionStatus

    /**
     * Whether the holder may open the camera, or start a recording with audio, at [status]: the
     * camera's status for the one, the microphone's for the other. Granted everywhere; the desktop
     * checker also allows NotDetermined, because macOS prompts when the camera or the microphone opens.
     */
    public fun allowsOpen(status: PermissionStatus): Boolean = status == PermissionStatus.Granted

    /**
     * Whether opening the camera, or the microphone for [permission] MICROPHONE, at [status] shows the
     * platform's prompt and waits for the user's answer, so the holder lets that start take as long as
     * the user does. False everywhere but macOS, where it is true at a NotDetermined the checker read
     * from the OS, and false when the OS could not be asked.
     */
    public fun promptsOnOpen(permission: CameraKPermission, status: PermissionStatus): Boolean = false
}

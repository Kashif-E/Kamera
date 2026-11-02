package com.kashif.cameraK.permissions

/** Closed hierarchy: what the platform says about one [CameraKPermission]. Subtypes compare by value. */
public sealed interface PermissionStatus {
    public data object Granted : PermissionStatus

    /** Partial photo-library access (iOS 14 and later). */
    public data object Limited : PermissionStatus

    /** Denied, and the app may ask again. [shouldShowRationale] is Android's hint to explain first. */
    public class Denied(public val shouldShowRationale: Boolean) : PermissionStatus

    /** Denied, and only the system settings can grant it now. */
    public data object PermanentlyDenied : PermissionStatus

    /** Blocked by device policy or parental controls; neither the app nor the user can grant it. */
    public data object Restricted : PermissionStatus

    /** Not asked yet. */
    public data object NotDetermined : PermissionStatus
}

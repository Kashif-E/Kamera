package com.kashif.cameraK.permissions

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.utils.InternalCameraKApi

@OptIn(InternalCameraKApi::class)
internal actual fun platformPermissionChecker(context: CameraKPlatformContext): CameraKPermissionChecker =
    CameraKPermissionChecker { platformPermissionStatus(context, it) }

/**
 * Android's status of [permission]. A grant is Granted, and clears a recorded permanent denial,
 * because the user may have granted it in Settings. Otherwise it is what [PermissionRequestRecord]
 * holds: PermanentlyDenied when `:cameraK`'s request inferred it, Denied after any earlier request,
 * and NotDetermined when the app never asked. A permission the API level does not need, such as
 * the gallery from API 29, is Granted. The holder's checker and `:cameraK`'s permission state both
 * read this.
 */
@InternalCameraKApi
public fun platformPermissionStatus(context: CameraKPlatformContext, permission: CameraKPermission): PermissionStatus {
    val name = androidPermissionName(permission) ?: return PermissionStatus.Granted
    val app = context.applicationContext
    val record = PermissionRequestRecord(app)
    if (app.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED) {
        record.clearPermanentDenial(name)
        return PermissionStatus.Granted
    }
    return when {
        record.isPermanentlyDenied(name) -> PermissionStatus.PermanentlyDenied
        // Without an Activity core cannot ask whether to show a rationale; a request that may be repeated may want one.
        record.wasAsked(name) -> PermissionStatus.Denied(shouldShowRationale = true)
        else -> PermissionStatus.NotDetermined
    }
}

/**
 * The runtime permission [permission] needs on this API level, or null when it needs none: MediaStore
 * inserts of the app's own media need no permission from API 29.
 */
@InternalCameraKApi
public fun androidPermissionName(permission: CameraKPermission): String? = when (permission) {
    CameraKPermission.CAMERA -> Manifest.permission.CAMERA
    CameraKPermission.MICROPHONE -> Manifest.permission.RECORD_AUDIO
    CameraKPermission.GALLERY_ADD ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) null else Manifest.permission.WRITE_EXTERNAL_STORAGE
}

package com.kashif.cameraK.permissions

import com.kashif.cameraK.controller.isOsAtLeast
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.utils.InternalCameraKApi
import platform.AVFoundation.AVAuthorizationStatus
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaType
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.Photos.PHAccessLevel
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAuthorizationStatus
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusDenied
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHAuthorizationStatusRestricted
import platform.Photos.PHPhotoLibrary
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@OptIn(InternalCameraKApi::class)
internal actual fun platformPermissionChecker(context: CameraKPlatformContext): CameraKPermissionChecker =
    CameraKPermissionChecker { platformPermissionStatus(context, it) }

/** The AVFoundation and Photos calls behind the iOS statuses and requests. Tests replace it; the simulator's own cannot be scripted. */
internal interface ApplePermissionPlatform {
    fun captureStatus(mediaType: AVMediaType): AVAuthorizationStatus

    fun photoStatus(accessLevel: PHAccessLevel): PHAuthorizationStatus

    /** Shows the system prompt for [mediaType]; [completion] may run on any queue. */
    fun requestCapture(mediaType: AVMediaType, completion: (granted: Boolean) -> Unit)

    /** Shows the system prompt for the photo library at [accessLevel]; [completion] may run on any queue. */
    fun requestPhoto(accessLevel: PHAccessLevel, completion: (PHAuthorizationStatus) -> Unit)
}

private object SystemPermissionPlatform : ApplePermissionPlatform {
    override fun captureStatus(mediaType: AVMediaType): AVAuthorizationStatus =
        AVCaptureDevice.authorizationStatusForMediaType(mediaType)

    // The access levels arrived in iOS 14; before it the library has one level, the full one.
    override fun photoStatus(accessLevel: PHAccessLevel): PHAuthorizationStatus = if (isOsAtLeast(14)) {
        PHPhotoLibrary.authorizationStatusForAccessLevel(accessLevel)
    } else {
        PHPhotoLibrary.authorizationStatus()
    }

    override fun requestCapture(mediaType: AVMediaType, completion: (granted: Boolean) -> Unit) {
        AVCaptureDevice.requestAccessForMediaType(mediaType) { granted -> completion(granted) }
    }

    override fun requestPhoto(accessLevel: PHAccessLevel, completion: (PHAuthorizationStatus) -> Unit) {
        if (isOsAtLeast(14)) {
            PHPhotoLibrary.requestAuthorizationForAccessLevel(accessLevel) { status -> completion(status) }
        } else {
            PHPhotoLibrary.requestAuthorization { status -> completion(status) }
        }
    }
}

internal var applePermissionPlatform: ApplePermissionPlatform = SystemPermissionPlatform

/**
 * iOS's status of [permission]: AVFoundation's for the camera (`AVMediaTypeVideo`) and the
 * microphone (`AVMediaTypeAudio`), and the photo library's add-only level for the gallery, which is
 * all a capture saved to Photos needs. Denied is PermanentlyDenied, because iOS shows its prompt only
 * once. The holder's checker and `:cameraK`'s permission state both read this.
 */
@InternalCameraKApi
public fun platformPermissionStatus(context: CameraKPlatformContext, permission: CameraKPermission): PermissionStatus {
    val platform = applePermissionPlatform
    return when (permission) {
        CameraKPermission.CAMERA -> captureStatusOf(platform.captureStatus(AVMediaTypeVideo))
        CameraKPermission.MICROPHONE -> captureStatusOf(platform.captureStatus(AVMediaTypeAudio))
        CameraKPermission.GALLERY_ADD -> photoStatusOf(platform.photoStatus(PHAccessLevelAddOnly))
    }
}

/**
 * Shows the system prompt for each of [permissions] iOS has not asked about yet, one after another,
 * then calls [onResult] on the main thread with every status. A permission already answered gets no
 * prompt: iOS never shows one twice, and the user changes it in Settings.
 */
@InternalCameraKApi
public fun requestPlatformPermissions(
    context: CameraKPlatformContext,
    permissions: List<CameraKPermission>,
    onResult: (Map<CameraKPermission, PermissionStatus>) -> Unit,
) {
    val platform = applePermissionPlatform
    val unasked = permissions.distinct().filter {
        platformPermissionStatus(context, it) ==
            PermissionStatus.NotDetermined
    }

    fun requestFrom(index: Int) {
        if (index == unasked.size) {
            dispatch_async(dispatch_get_main_queue()) {
                onResult(permissions.associateWith { platformPermissionStatus(context, it) })
            }
            return
        }
        when (unasked[index]) {
            CameraKPermission.CAMERA -> platform.requestCapture(AVMediaTypeVideo) { requestFrom(index + 1) }
            CameraKPermission.MICROPHONE -> platform.requestCapture(AVMediaTypeAudio) { requestFrom(index + 1) }
            CameraKPermission.GALLERY_ADD -> platform.requestPhoto(PHAccessLevelAddOnly) { requestFrom(index + 1) }
        }
    }
    requestFrom(0)
}

private fun captureStatusOf(status: AVAuthorizationStatus): PermissionStatus = when (status) {
    AVAuthorizationStatusAuthorized -> PermissionStatus.Granted
    AVAuthorizationStatusDenied -> PermissionStatus.PermanentlyDenied
    AVAuthorizationStatusRestricted -> PermissionStatus.Restricted
    // NotDetermined, and any status a later iOS adds: neither is a grant.
    else -> PermissionStatus.NotDetermined
}

private fun photoStatusOf(status: PHAuthorizationStatus): PermissionStatus = when (status) {
    PHAuthorizationStatusAuthorized -> PermissionStatus.Granted
    PHAuthorizationStatusLimited -> PermissionStatus.Limited
    PHAuthorizationStatusDenied -> PermissionStatus.PermanentlyDenied
    PHAuthorizationStatusRestricted -> PermissionStatus.Restricted
    else -> PermissionStatus.NotDetermined
}

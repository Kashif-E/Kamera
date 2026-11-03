@file:Suppress("DEPRECATION")

package com.kashif.cameraK.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.utils.InternalCameraKApi
import platform.Photos.PHAuthorizationStatus
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHPhotoLibrary
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS-specific implementation of [Permissions].
 * Handles camera and photo library permissions using AVFoundation and PhotoKit frameworks. Callbacks
 * run on the main thread.
 */
@OptIn(InternalCameraKApi::class)
private class IOSPermissionsImpl : Permissions {
    private val context = CameraKPlatformContext()

    /**
     * Checks if the app has authorization to use the camera.
     *
     * @return True if camera permission is authorized.
     */
    override fun hasCameraPermission(): Boolean =
        platformPermissionStatus(context, CameraKPermission.CAMERA) == PermissionStatus.Granted

    /**
     * Checks if the app has authorization to access the photo library.
     * Accepts both full and limited (allowed assets only) authorization.
     *
     * @return True if storage/photo library permission is authorized.
     */
    override fun hasStoragePermission(): Boolean = PHPhotoLibrary.authorizationStatus().allowsAccess()

    /**
     * Requests camera permission and calls the appropriate callback.
     *
     * @param onGranted Invoked if permission is granted.
     * @param onDenied Invoked if permission is denied.
     */
    @Composable
    override fun RequestCameraPermission(onGranted: () -> Unit, onDenied: () -> Unit) {
        LaunchedEffect(Unit) {
            requestPlatformPermissions(context, listOf(CameraKPermission.CAMERA)) { statuses ->
                if (statuses[CameraKPermission.CAMERA] == PermissionStatus.Granted) onGranted() else onDenied()
            }
        }
    }

    /**
     * Requests photo library permission and calls the appropriate callback. It still asks for
     * read-write access, which needs `NSPhotoLibraryUsageDescription`, as 1.x did; limited access
     * counts as granted, as [hasStoragePermission] does.
     *
     * @param onGranted Invoked if permission is granted.
     * @param onDenied Invoked if permission is denied.
     */
    @Composable
    override fun RequestStoragePermission(onGranted: () -> Unit, onDenied: () -> Unit) {
        if (hasStoragePermission()) {
            LaunchedEffect(Unit) { onGranted() }
        } else {
            LaunchedEffect(Unit) {
                PHPhotoLibrary.requestAuthorization { status ->
                    dispatch_async(dispatch_get_main_queue()) {
                        if (status.allowsAccess()) onGranted() else onDenied()
                    }
                }
            }
        }
    }

    private fun PHAuthorizationStatus.allowsAccess(): Boolean =
        this == PHAuthorizationStatusAuthorized || this == PHAuthorizationStatusLimited
}

/**
 * Factory function to provide platform-specific [Permissions] implementation.
 */
@Deprecated(
    "Use rememberCameraKPermissionState(CameraKPermission.CAMERA) and call launchRequest() from an event " +
        "handler. Removed in 2.0.",
)
@Composable
actual fun providePermissions(): Permissions = remember { IOSPermissionsImpl() }

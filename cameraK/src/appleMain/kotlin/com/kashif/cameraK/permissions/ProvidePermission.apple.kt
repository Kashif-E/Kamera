package com.kashif.cameraK.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHPhotoLibrary

/**
 * iOS-specific implementation of [Permissions].
 * Handles camera and photo library permissions using AVFoundation and PhotoKit frameworks.
 */
private class IOSPermissionsImpl : Permissions {
    /**
     * Checks if the app has authorization to use the camera.
     *
     * @return True if camera permission is authorized.
     */
    override fun hasCameraPermission(): Boolean =
        AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) == AVAuthorizationStatusAuthorized

    /**
     * Checks if the app has authorization to access the photo library.
     * Accepts both full and limited (allowed assets only) authorization.
     *
     * @return True if storage/photo library permission is authorized.
     */
    override fun hasStoragePermission(): Boolean {
        val status = PHPhotoLibrary.authorizationStatus()
        return status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited
    }

    /**
     * Requests camera permission and calls the appropriate callback.
     *
     * @param onGranted Invoked if permission is granted.
     * @param onDenied Invoked if permission is denied.
     */
    @Composable
    override fun RequestCameraPermission(onGranted: () -> Unit, onDenied: () -> Unit) {
        if (hasCameraPermission()) {
            LaunchedEffect(Unit) { onGranted() }
        } else {
            LaunchedEffect(Unit) {
                AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                    if (granted) onGranted() else onDenied()
                }
            }
        }
    }

    /**
     * Requests photo library permission and calls the appropriate callback.
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
                    if (status == PHAuthorizationStatusAuthorized) onGranted() else onDenied()
                }
            }
        }
    }
}

/**
 * Factory function to provide platform-specific [Permissions] implementation.
 */
@Composable
actual fun providePermissions(): Permissions = remember { IOSPermissionsImpl() }

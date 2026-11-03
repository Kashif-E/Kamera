package com.kashif.cameraK.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable

/**
 * Interface for requesting permissions in a platform-agnostic way.
 *
 * Deprecated: it prompts as a side effect of composition and reports only granted or denied. Use
 * [rememberCameraKPermissionState], whose statuses tell a permanent denial from one the app may ask
 * again, and whose requests are explicit calls.
 */
@Deprecated(
    "Use rememberCameraKPermissionState(CameraKPermission.CAMERA) and call launchRequest() from an event " +
        "handler. Removed in 2.0.",
)
@Stable
interface Permissions {

    /**
     * Checks if the camera permission is granted.
     *
     * @return True if granted, false otherwise.
     */
    fun hasCameraPermission(): Boolean

    /**
     * Checks if the storage permission is granted.
     *
     * @return True if granted or not required, false otherwise.
     */
    fun hasStoragePermission(): Boolean

    /**
     * Requests camera permission.
     *
     * @param onGranted Callback invoked when permission is granted.
     * @param onDenied Callback invoked when permission is denied.
     */
    @Composable
    fun RequestCameraPermission(onGranted: () -> Unit, onDenied: () -> Unit)

    /**
     * Requests storage permission.
     *
     * @param onGranted Callback invoked when permission is granted.
     * @param onDenied Callback invoked when permission is denied.
     */

    @Composable
    fun RequestStoragePermission(onGranted: () -> Unit, onDenied: () -> Unit)
}

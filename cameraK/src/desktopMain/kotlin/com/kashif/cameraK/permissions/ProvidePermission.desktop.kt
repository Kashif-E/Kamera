@file:Suppress("DEPRECATION")

package com.kashif.cameraK.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/**
 * Desktop-specific implementation of [Permissions].
 * On desktop platforms, permissions are automatically granted (no permission system).
 */
@Deprecated(
    "Use rememberCameraKPermissionState(CameraKPermission.CAMERA) and call launchRequest() from an event " +
        "handler. Removed in 2.0.",
)
@Composable
actual fun providePermissions(): Permissions = remember {
    /**
     * Desktop permissions provider - all permissions are automatically allowed.
     */
    object : Permissions {
        override fun hasCameraPermission(): Boolean = true

        override fun hasStoragePermission(): Boolean = true

        // From an effect, so the grant runs once and not in the composable's body on every recomposition.
        @Composable
        override fun RequestCameraPermission(onGranted: () -> Unit, onDenied: () -> Unit) {
            LaunchedEffect(Unit) { onGranted() }
        }

        @Composable
        override fun RequestStoragePermission(onGranted: () -> Unit, onDenied: () -> Unit) {
            LaunchedEffect(Unit) { onGranted() }
        }
    }
}

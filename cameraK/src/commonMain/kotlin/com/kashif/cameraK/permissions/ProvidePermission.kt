@file:Suppress("DEPRECATION")

package com.kashif.cameraK.permissions

import androidx.compose.runtime.Composable

/**
 * Factory function to provide platform-specific [Permissions] implementation.
 */
@Deprecated(
    "Use rememberCameraKPermissionState(CameraKPermission.CAMERA) and call launchRequest() from an event " +
        "handler. Removed in 2.0.",
)
@Composable
expect fun providePermissions(): Permissions

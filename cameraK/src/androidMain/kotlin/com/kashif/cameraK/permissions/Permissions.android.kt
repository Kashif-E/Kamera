@file:Suppress("DEPRECATION")

package com.kashif.cameraK.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Android-specific implementation of [Permissions] using Activity Result Contracts.
 * Simplified internal implementation that removes redundant permission checks.
 */
@Deprecated(
    "Use rememberCameraKPermissionState(CameraKPermission.CAMERA) and call launchRequest() from an event " +
        "handler. Removed in 2.0.",
)
@Composable
actual fun providePermissions(): Permissions {
    val context = LocalContext.current

    return remember(context) {
        object : Permissions {
            override fun hasCameraPermission(): Boolean =
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED

            override fun hasStoragePermission(): Boolean = storagePermissionGranted(context)

            @Composable
            override fun RequestCameraPermission(onGranted: () -> Unit, onDenied: () -> Unit) {
                if (hasCameraPermission()) {
                    LaunchedEffect(Unit) { onGranted() }
                } else {
                    val launcher =
                        rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestPermission(),
                        ) { isGranted -> if (isGranted) onGranted() else onDenied() }

                    LaunchedEffect(Unit) {
                        launcher.launch(Manifest.permission.CAMERA)
                    }
                }
            }

            @Composable
            override fun RequestStoragePermission(onGranted: () -> Unit, onDenied: () -> Unit) {
                if (hasStoragePermission()) {
                    LaunchedEffect(Unit) { onGranted() }
                } else {
                    val launcher =
                        rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestPermission(),
                        ) { isGranted -> if (isGranted) onGranted() else onDenied() }

                    LaunchedEffect(Unit) {
                        launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }
            }
        }
    }
}

internal fun storagePermissionGranted(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
    PackageManager.PERMISSION_GRANTED

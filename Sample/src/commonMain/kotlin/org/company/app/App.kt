package org.company.app

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.permissions.rememberCameraKPermissionState
import kotlinx.coroutines.launch
import org.company.app.theme.AppTheme

@Composable
fun App() = AppTheme {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val imageSaver = rememberImageSaverFeature()
    val analyzer = rememberAnalyzerFeature()
    val qrScanner = rememberQrScannerFeature()
    val ocr = rememberOcrFeature()

    val asked = remember(imageSaver) { samplePermissions(imageSaver.plugin.config.autoSave) }
    val permissions = rememberCameraKPermissionState(*asked.toTypedArray()) { results ->
        permissionNotice(results)?.let { scope.launch { snackbarHostState.showSnackbar(it) } }
    }
    // Asked once when the app opens, as the 1.x screen did.
    LaunchedEffect(permissions) {
        if (!permissions.allGranted) permissions.launchRequest()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
        // Camera app: black background so the preview re-bind on aspect-ratio change (and any
        // letterbox bars) never flashes the default white surface.
        containerColor = Color.Black,
    ) {
        if (cameraGranted(permissions.statuses[CameraKPermission.CAMERA])) {
            CameraContent(
                analyzer = analyzer,
                imageSaver = imageSaver,
                qrScanner = qrScanner,
                ocr = ocr,
                snackbarHostState = snackbarHostState,
            )
        }
    }
}

/**
 * What the Sample asks for when it opens: the camera, and the gallery only when the image saver puts
 * every capture there. On Android 10 and later and on desktop the gallery needs no permission and reads
 * Granted.
 */
internal fun samplePermissions(autoSave: Boolean): List<CameraKPermission> =
    if (autoSave) listOf(CameraKPermission.CAMERA, CameraKPermission.GALLERY_ADD) else listOf(CameraKPermission.CAMERA)

/** Whether the Sample shows the camera for this camera permission status; Limited counts as granted. */
internal fun cameraGranted(status: PermissionStatus?): Boolean =
    status == PermissionStatus.Granted || status == PermissionStatus.Limited

/**
 * The line the screen shows when the camera was refused; a refused gallery only stops the saving, and a
 * camera not asked yet is no refusal.
 */
internal fun permissionNotice(results: Map<CameraKPermission, PermissionStatus>): String? =
    when (results[CameraKPermission.CAMERA]) {
        null, PermissionStatus.Granted, PermissionStatus.Limited, PermissionStatus.NotDetermined -> null
        PermissionStatus.PermanentlyDenied -> "Allow camera access in Settings"
        PermissionStatus.Restricted -> "Camera access is restricted on this device"
        is PermissionStatus.Denied -> "Camera access was denied"
    }

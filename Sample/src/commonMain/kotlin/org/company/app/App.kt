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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.permissions.rememberCameraKPermissionState
import org.company.app.theme.AppTheme

@Composable
fun App() = AppTheme {
    // The camera, and the gallery the image saver publishes to; on Android 10 and later and on desktop
    // the gallery needs no permission and reads Granted.
    val permissions = rememberCameraKPermissionState(CameraKPermission.CAMERA, CameraKPermission.GALLERY_ADD) {
        if (it[CameraKPermission.CAMERA] != PermissionStatus.Granted) println("Camera Permission Denied")
    }
    // Asked once when the app opens, as the 1.x screen did.
    LaunchedEffect(permissions) {
        if (!permissions.allGranted) permissions.launchRequest()
    }
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
        // Camera app: black background so the preview re-bind on aspect-ratio change (and any
        // letterbox bars) never flashes the default white surface.
        containerColor = Color.Black,
    ) {
        val imageSaver = rememberImageSaverFeature()
        val analyzer = rememberAnalyzerFeature()
        val qrScanner = rememberQrScannerFeature()
        val ocr = rememberOcrFeature()

        if (permissions.statuses[CameraKPermission.CAMERA] == PermissionStatus.Granted) {
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

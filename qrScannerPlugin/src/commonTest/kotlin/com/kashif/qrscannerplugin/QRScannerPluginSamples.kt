package com.kashif.qrscannerplugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kashif.cameraK.compose.rememberCameraKState
import com.kashif.cameraK.state.CameraKState

// KDoc samples for QRScannerPlugin, referenced with @sample. They live here so the compiler checks them.

@Composable
internal fun ScanQrCodesSample(onCode: (String) -> Unit) {
    val qrPlugin = rememberQRScannerPlugin()
    var detectedQr by remember { mutableStateOf<String?>(null) }
    val cameraState by rememberCameraKState { stateHolder ->
        stateHolder.attachPlugin(qrPlugin)
    }

    LaunchedEffect(qrPlugin) {
        qrPlugin.getQrCodeFlow().collect { qr ->
            detectedQr = qr
            onCode(qr)
        }
    }

    if (cameraState is CameraKState.Ready) {
        // Show the preview and detectedQr.
    }
}

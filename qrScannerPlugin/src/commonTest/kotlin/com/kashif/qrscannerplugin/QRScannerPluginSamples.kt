package com.kashif.qrscannerplugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kashif.cameraK.state.CameraKStateHolder

// KDoc samples for QRScannerPlugin, referenced with @sample. They live here so the compiler checks them.

@Composable
internal fun ScanQrCodesSample(stateHolder: CameraKStateHolder, onCode: (String) -> Unit) {
    val qrPlugin = rememberQRScannerPlugin(
        QRScannerConfig {
            formats =
                setOf(BarcodeFormat.QR_CODE, BarcodeFormat.EAN_13)
        },
    )
    var detected by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(stateHolder, qrPlugin) {
        stateHolder.attachPlugin(qrPlugin)
    }
    LaunchedEffect(qrPlugin) {
        qrPlugin.results.collect { barcode ->
            // The payload is untrusted: check it before acting on it.
            val text = barcode.rawValue ?: return@collect
            detected = text
            onCode(text)
        }
    }
    // Show the preview and detected; qrPlugin.pause() and resume() stop and restart decoding.
}

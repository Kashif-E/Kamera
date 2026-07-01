package com.kashif.qrscannerplugin

import com.kashif.cameraK.controller.CameraController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Platform-specific function to start scanning for QR codes.
 *
 * @param controller The CameraController to be used for scanning.
 * @param onQrScanner A callback function that is invoked when a QR code is scanned.
 */
actual fun startScanning(controller: CameraController, onQrScanner: (String) -> Unit): ScannerHandle {
    val qrScanner = QRScanner()
    val scope = CoroutineScope(Dispatchers.Default)

    scope.launch {
        controller.frameFlow.collect { image ->
            qrScanner.scanImage(image)?.let { code ->
                withContext(Dispatchers.Main) {
                    onQrScanner(code)
                }
            }
        }
    }

    // Without this the frame collector ran forever after detach.
    return ScannerHandle { scope.cancel() }
}

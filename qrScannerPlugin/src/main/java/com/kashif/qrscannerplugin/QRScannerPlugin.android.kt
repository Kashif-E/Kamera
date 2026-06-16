package com.kashif.qrscannerplugin

import android.graphics.ImageFormat
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.kashif.cameraK.controller.CameraController
import java.util.EnumMap

/**
 * Enables QR code and barcode scanning on this camera controller.
 *
 * @param onQrScanner Callback invoked when a QR code is detected with the scanned text
 */
fun CameraController.enableQrCodeScanner(onQrScanner: (String) -> Unit) {
    Log.d("QRScanner", "Enabling QR code scanner")
    try {
        registerImageAnalyzer(QRCodeAnalyzer(onQrScanner))
    } catch (e: Exception) {
        Log.e("QRScanner", "Failed to enable QR scanner: ${e.message}", e)
        // Camera might not be fully initialized yet - this is expected during startup
    }
}

/**
 * Internal analyzer for QR codes and barcodes using ZXing library.
 *
 * Processes camera frames to detect and decode QR codes and common barcode formats
 * (EAN-13, EAN-8, CODE-128, CODE-39, UPC-A, UPC-E). Implements debouncing to prevent
 * duplicate detections within 1 second.
 *
 * @param onQrScanner Callback invoked when a QR code is successfully decoded
 */
private class QRCodeAnalyzer(private val onQrScanner: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val decodeHints =
        EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
            put(DecodeHintType.CHARACTER_SET, "UTF-8")
            // Spend more effort per frame: rotated 1D barcodes, harder-to-read codes.
            put(DecodeHintType.TRY_HARDER, true)
            put(
                DecodeHintType.POSSIBLE_FORMATS,
                listOf(
                    com.google.zxing.BarcodeFormat.QR_CODE,
                    com.google.zxing.BarcodeFormat.EAN_13,
                    com.google.zxing.BarcodeFormat.EAN_8,
                    com.google.zxing.BarcodeFormat.CODE_128,
                    com.google.zxing.BarcodeFormat.CODE_39,
                    com.google.zxing.BarcodeFormat.UPC_A,
                    com.google.zxing.BarcodeFormat.UPC_E,
                ),
            )
        }
    private val reader = MultiFormatReader().apply { setHints(decodeHints) }
    private var lastScannedCode: String? = null
    private var lastScanTime: Long = 0
    private val scanDebounceMs = 1000L

    /**
     * Analyzes camera frames to detect QR codes and barcodes.
     *
     * Converts YUV_420_888 frame data to RGB for ZXing processing and applies debouncing
     * to prevent repeated detections of the same code.
     *
     * @param imageProxy The camera frame to analyze
     */
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val image = imageProxy.image
        if (image == null) {
            imageProxy.close()
            return
        }

        if (image.format != ImageFormat.YUV_420_888) {
            Log.e("QRScanner", "Unsupported image format: ${image.format}")
            imageProxy.close()
            return
        }

        try {
            // The Y plane of YUV_420_888 IS the luminance ZXing wants — use it directly via
            // PlanarYUVLuminanceSource (faster and correct). The previous RGBLuminanceSource
            // path fed the luma byte in as the blue channel, producing a dim/wrong luminance.
            // rowStride is honored so row padding doesn't skew the image.
            val plane = image.planes[0]
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val source =
                PlanarYUVLuminanceSource(
                    bytes,
                    plane.rowStride,
                    image.height,
                    0,
                    0,
                    image.width,
                    image.height,
                    false,
                )

            val result =
                try {
                    reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
                } catch (_: NotFoundException) {
                    // Retry inverted so white-on-black / light-on-dark codes scan too (#116).
                    // ponytail: one extra decode per empty frame; CameraX drops frames under
                    // load so this self-throttles. Revisit if battery cost shows up.
                    reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert())))
                }
            val currentTime = System.currentTimeMillis()

            if (result.text != lastScannedCode || (currentTime - lastScanTime) > scanDebounceMs) {
                Log.d("QRScanner", "QR Code detected: ${result.text}")
                lastScannedCode = result.text
                lastScanTime = currentTime
                onQrScanner(result.text)
            }
        } catch (e: Exception) {
            // QR code detection failed - no code found in frame (expected during normal scanning)
        } finally {
            reader.reset()
            imageProxy.close()
        }
    }
}

actual fun startScanning(controller: CameraController, onQrScanner: (String) -> Unit) {
    Log.d("QRScanner", "Starting QR scanner")
    controller.enableQrCodeScanner(onQrScanner)
}

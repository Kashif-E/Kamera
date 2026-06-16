package com.kashif.qrscannerplugin

import com.kashif.cameraK.controller.CameraController
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeAztecCode
import platform.AVFoundation.AVMetadataObjectTypeCode128Code
import platform.AVFoundation.AVMetadataObjectTypeCode39Code
import platform.AVFoundation.AVMetadataObjectTypeCode39Mod43Code
import platform.AVFoundation.AVMetadataObjectTypeCode93Code
import platform.AVFoundation.AVMetadataObjectTypeDataMatrixCode
import platform.AVFoundation.AVMetadataObjectTypeEAN13Code
import platform.AVFoundation.AVMetadataObjectTypeEAN8Code
import platform.AVFoundation.AVMetadataObjectTypeITF14Code
import platform.AVFoundation.AVMetadataObjectTypeInterleaved2of5Code
import platform.AVFoundation.AVMetadataObjectTypePDF417Code
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.AVMetadataObjectTypeUPCECode
import platform.darwin.NSObject

/**
 * Represents a scanned code result from iOS camera metadata.
 *
 * Supports QR codes and common barcode formats.
 */
sealed class ScannedCode {
    abstract val value: String
    abstract val type: String

    /**
     * QR code result.
     *
     * @param value The decoded QR code text content
     */
    data class QR(override val value: String) : ScannedCode() {
        override val type: String = "QR_CODE"
    }

    /**
     * Barcode result (EAN, CODE, PDF417, Aztec, DataMatrix, UPC).
     *
     * @param value The decoded barcode text content
     * @param type The barcode format type (e.g., "EAN_13", "CODE_128")
     */
    data class Barcode(override val value: String, override val type: String) : ScannedCode()

    companion object {
        /**
         * Converts an AVFoundation metadata object to a [ScannedCode].
         *
         * @param metadata The AVFoundation machine-readable code object
         * @return A [ScannedCode] if successful, null if metadata type is unsupported or empty
         */
        fun fromAVMetadata(metadata: AVMetadataMachineReadableCodeObject): ScannedCode? {
            val value = metadata.stringValue ?: return null
            return when (metadata.type) {
                AVMetadataObjectTypeQRCode -> QR(value)
                AVMetadataObjectTypeEAN13Code -> Barcode(value, "EAN_13")
                AVMetadataObjectTypeEAN8Code -> Barcode(value, "EAN_8")
                AVMetadataObjectTypeCode128Code -> Barcode(value, "CODE_128")
                AVMetadataObjectTypeCode39Code -> Barcode(value, "CODE_39")
                AVMetadataObjectTypeCode93Code -> Barcode(value, "CODE_93")
                AVMetadataObjectTypeCode39Mod43Code -> Barcode(value, "CODE_39_MOD_43")
                AVMetadataObjectTypeITF14Code -> Barcode(value, "ITF_14")
                AVMetadataObjectTypeInterleaved2of5Code -> Barcode(value, "ITF")
                AVMetadataObjectTypePDF417Code -> Barcode(value, "PDF_417")
                AVMetadataObjectTypeAztecCode -> Barcode(value, "AZTEC")
                AVMetadataObjectTypeDataMatrixCode -> Barcode(value, "DATA_MATRIX")
                AVMetadataObjectTypeUPCECode -> Barcode(value, "UPC_E")
                else -> null
            }
        }
    }
}

/**
 * Enables QR code and barcode scanning on the iOS camera controller.
 *
 * Uses configuration queue pattern to safely add outputs without crashes.
 * Configures AVCaptureMetadataOutput to detect QR codes and standard barcode formats
 * (EAN-13, EAN-8, CODE-128, CODE-39, CODE-93, ITF-14, PDF-417, Aztec, DataMatrix, UPC-E).
 *
 * @param controller The camera controller to enable scanning on
 * @param onQrScanner Callback invoked when a QR code is detected with the scanned text
 */
actual fun startScanning(controller: CameraController, onQrScanner: (String) -> Unit) {
    val codeAnalyzer =
        CodeAnalyzer(onCodeScanned = {
            onQrScanner(it.value)
        })
    controller.setMetadataObjectsDelegate(codeAnalyzer)

    // Queue all configuration changes atomically (WWDC pattern)
    controller.queueConfigurationChange {
        controller.updateMetadataObjectTypes(
            listOf(
                AVMetadataObjectTypeQRCode!!,
                AVMetadataObjectTypeEAN13Code!!,
                AVMetadataObjectTypeEAN8Code!!,
                AVMetadataObjectTypeCode128Code!!,
                AVMetadataObjectTypeCode39Code!!,
                AVMetadataObjectTypeCode93Code!!,
                AVMetadataObjectTypeCode39Mod43Code!!,
                AVMetadataObjectTypeITF14Code!!,
                AVMetadataObjectTypeInterleaved2of5Code!!,
                AVMetadataObjectTypePDF417Code!!,
                AVMetadataObjectTypeAztecCode!!,
                AVMetadataObjectTypeDataMatrixCode!!,
                AVMetadataObjectTypeUPCECode!!,
            ),
        )
    }
}

/**
 * Internal iOS metadata output delegate for QR code and barcode detection.
 *
 * Uses AVCaptureMetadataOutputObjectsDelegateProtocol to receive metadata objects from
 * the camera. Implements debouncing via atomic flag to process one code at a time.
 *
 * @param onCodeScanned Callback invoked when a QR code or barcode is successfully detected
 */
private class CodeAnalyzer(private val onCodeScanned: (ScannedCode) -> Unit) :
    NSObject(),
    AVCaptureMetadataOutputObjectsDelegateProtocol {
    private val isProcessing = atomic(false)
    private val scope = CoroutineScope(Dispatchers.Main)
    private val debounceMs = 500L

    /**
     * Processes metadata objects detected by the camera.
     *
     * @param captureOutput The metadata output instance
     * @param didOutputMetadataObjects List of detected metadata objects
     * @param fromConnection The capture connection
     */
    override fun captureOutput(
        captureOutput: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection,
    ) {
        if (isProcessing.value) return

        for (metadata in didOutputMetadataObjects) {
            if (metadata !is AVMetadataMachineReadableCodeObject) continue
            val scannedCode = ScannedCode.fromAVMetadata(metadata) ?: continue
            processCode(scannedCode)
        }
    }

    /**
     * Processes a scanned code with timeout-based debouncing.
     *
     * @param code The detected code to process
     */
    private fun processCode(code: ScannedCode) {
        if (isProcessing.compareAndSet(expect = false, update = true)) {
            scope.launch {
                try {
                    onCodeScanned(code)
                    delay(debounceMs)
                } finally {
                    isProcessing.value = false
                }
            }
        }
    }
}

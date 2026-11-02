package com.kashif.qrscannerplugin

import android.graphics.ImageFormat
import android.graphics.Rect
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.utils.CameraKLogger
import java.util.EnumMap

/**
 * Enables QR code and barcode scanning on this camera controller.
 *
 * @param onQrScanner Callback invoked when a QR code is detected with the scanned text
 */
fun CameraController.enableQrCodeScanner(onQrScanner: (String) -> Unit): ImageAnalysis.Analyzer? =
    enableQrCodeScanner(DEFAULT_QR_SCAN_INTERVAL_MS, onQrScanner)

internal fun CameraController.enableQrCodeScanner(
    scanIntervalMs: Long,
    onQrScanner: (String) -> Unit,
): ImageAnalysis.Analyzer? {
    CameraKLogger.d("QRScanner", "Enabling QR code scanner")
    return try {
        val analyzer = QRCodeAnalyzer(onQrScanner, scanIntervalMs = scanIntervalMs)
        registerImageAnalyzer(analyzer)
        analyzer
    } catch (e: Exception) {
        CameraKLogger.e("QRScanner", "Failed to enable QR scanner: ${e.message}", e)
        // Camera might not be fully initialized yet - this is expected during startup
        null
    }
}

/**
 * A region of 8-bit luma for ZXing: [data] holds [dataHeight] rows of [dataWidth] bytes, and the
 * decoder reads the [width] x [height] rectangle at ([left], [top]).
 */
internal class LumaFrame(
    val data: ByteArray,
    val dataWidth: Int,
    val dataHeight: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/** One ZXing decode of a luma region, so a test can count and inspect attempts. */
internal fun interface LumaDecoder {
    /**
     * Returns the decoded text, or null when the region holds no readable code.
     *
     * @param tryHarder Spend more time looking for a hard-to-read code.
     * @param inverted Look for a light-on-dark code.
     */
    fun decode(frame: LumaFrame, tryHarder: Boolean, inverted: Boolean): String?
}

/** [LumaDecoder] over ZXing's [MultiFormatReader], for QR codes and common 1D barcodes. */
internal class ZxingLumaDecoder : LumaDecoder {
    private val fastReader = reader(tryHarder = false)

    // Spends more effort per frame: rotated 1D barcodes, harder-to-read codes.
    private val slowReader = reader(tryHarder = true)

    override fun decode(frame: LumaFrame, tryHarder: Boolean, inverted: Boolean): String? {
        val source =
            PlanarYUVLuminanceSource(
                frame.data,
                frame.dataWidth,
                frame.dataHeight,
                frame.left,
                frame.top,
                frame.width,
                frame.height,
                false,
            )
        val reader = if (tryHarder) slowReader else fastReader
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(if (inverted) source.invert() else source))).text
        } catch (expected: NotFoundException) {
            null
        } finally {
            reader.reset()
        }
    }

    private fun reader(tryHarder: Boolean) = MultiFormatReader().apply {
        setHints(
            EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
                put(DecodeHintType.CHARACTER_SET, "UTF-8")
                if (tryHarder) put(DecodeHintType.TRY_HARDER, true)
                put(
                    DecodeHintType.POSSIBLE_FORMATS,
                    listOf(
                        BarcodeFormat.QR_CODE,
                        BarcodeFormat.EAN_13,
                        BarcodeFormat.EAN_8,
                        BarcodeFormat.CODE_128,
                        BarcodeFormat.CODE_39,
                        BarcodeFormat.UPC_A,
                        BarcodeFormat.UPC_E,
                    ),
                )
            },
        )
    }
}

/**
 * Analyzer for QR codes and barcodes using ZXing library.
 *
 * Processes camera frames to detect and decode QR codes and common barcode formats
 * (EAN-13, EAN-8, CODE-128, CODE-39, UPC-A, UPC-E). Implements debouncing to prevent
 * duplicate detections within 1 second.
 *
 * @param onQrScanner Callback invoked when a QR code is successfully decoded
 * @param scanIntervalMs The shortest time between two decode attempts, found or not.
 */
internal class QRCodeAnalyzer(
    private val onQrScanner: (String) -> Unit,
    private val decoder: LumaDecoder = ZxingLumaDecoder(),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val scanIntervalMs: Long = DEFAULT_QR_SCAN_INTERVAL_MS,
) : ImageAnalysis.Analyzer {
    init {
        require(scanIntervalMs >= 0) { "scanIntervalMs must not be negative" }
    }

    // When the last decode attempt started; frames that arrive sooner are closed unread.
    private var lastAttemptTime = Long.MIN_VALUE / 2

    private var lastScannedCode: String? = null
    private var lastScanTime: Long = 0
    private val scanDebounceMs = 1000L

    // Inverted (white-on-black) codes are uncommon, and the retry costs a second full ZXing
    // decode. Only attempt it every Nth frame so normal scanning isn't doing two decodes on
    // every empty frame — still catches an inverted code within a few frames (#116).
    private var frameCount = 0L
    private val invertedRetryEveryNFrames = 3

    // ZXing's TRY_HARDER pass costs several fast passes; one frame in this many uses it.
    private val slowPassEveryNFrames = 5

    // The Y plane is copied here, reused while frames keep their size: analysis runs at frame rate.
    private var luma = ByteArray(0)

    // Decode failures other than "no readable code"; the first and then one in LOG_FAILURE_EVERY are
    // logged, so a decoder that fails on every frame shows without flooding the log.
    private var unexpectedFailures = 0L

    /**
     * Analyzes camera frames to detect QR codes and barcodes.
     *
     * Reads the Y plane of the YUV_420_888 frame as luma into a reused buffer, decodes inside the
     * frame's crop rect, and applies debouncing to prevent repeated detections of the same code.
     * At most one frame per scan interval is decoded; the others are closed unread. ZXing's
     * TRY_HARDER pass runs on every fifth attempt only.
     *
     * @param imageProxy The camera frame to analyze
     */
    override fun analyze(imageProxy: ImageProxy) {
        // The throttle counts attempts, not successes, and runs before the frame is read.
        val now = clock()
        if (now - lastAttemptTime < scanIntervalMs) {
            imageProxy.close()
            return
        }
        lastAttemptTime = now

        if (imageProxy.format != ImageFormat.YUV_420_888) {
            CameraKLogger.e("QRScanner", "Unsupported image format: ${imageProxy.format}")
            imageProxy.close()
            return
        }

        val text =
            try {
                decode(imageProxy)
            } catch (expected: ReaderException) {
                // ZXing found no readable code in the frame: the normal case while scanning.
                null
            } catch (e: Exception) {
                if (unexpectedFailures++ % LOG_FAILURE_EVERY == 0L) {
                    CameraKLogger.e("QRScanner", "QR decode failed ($unexpectedFailures unexpected failures)", e)
                }
                null
            } finally {
                imageProxy.close()
            }

        // Outside the try: an exception from the consumer's callback is the consumer's, not an empty frame.
        if (text != null) {
            val currentTime = System.currentTimeMillis()
            if (text != lastScannedCode || (currentTime - lastScanTime) > scanDebounceMs) {
                CameraKLogger.d("QRScanner", "QR Code detected")
                lastScannedCode = text
                lastScanTime = currentTime
                onQrScanner(text)
            }
        }
    }

    private fun decode(imageProxy: ImageProxy): String? {
        // The Y plane of YUV_420_888 IS the luminance ZXing wants — use it directly via
        // PlanarYUVLuminanceSource (faster and correct). The previous RGBLuminanceSource
        // path fed the luma byte in as the blue channel, producing a dim/wrong luminance.
        // rowStride is honored so row padding doesn't skew the image.
        val plane = imageProxy.planes[0]
        // Read through a duplicate from the start, so the copy is the full Y plane whatever
        // the buffer's position, and the proxy's buffer is left as it was.
        val buffer = plane.buffer.duplicate().apply { rewind() }
        val size = buffer.remaining()
        if (luma.size < size) luma = ByteArray(maxOf(size, plane.rowStride * imageProxy.height))
        buffer.get(luma, 0, size)

        // Decode only what the use case shows: the crop rect, clamped to the frame.
        val crop = Rect(imageProxy.cropRect)
        if (crop.isEmpty || !crop.intersect(0, 0, imageProxy.width, imageProxy.height)) {
            crop.set(0, 0, imageProxy.width, imageProxy.height)
        }
        val frame =
            LumaFrame(
                luma,
                plane.rowStride,
                imageProxy.height,
                crop.left,
                crop.top,
                crop.width(),
                crop.height(),
            )

        frameCount++
        val tryHarder = frameCount % slowPassEveryNFrames == 0L
        return decoder.decode(frame, tryHarder, inverted = false)
            // Periodic inverted pass for white-on-black / light-on-dark codes (#116).
            ?: if (frameCount % invertedRetryEveryNFrames == 0L) {
                decoder.decode(frame, tryHarder, inverted = true)
            } else {
                null
            }
    }

    private companion object {
        const val LOG_FAILURE_EVERY = 100L
    }
}

actual fun startScanning(controller: CameraController, onQrScanner: (String) -> Unit): ScannerHandle =
    startQrScanning(controller, DEFAULT_QR_SCAN_INTERVAL_MS, onQrScanner)

internal actual fun startQrScanning(
    controller: CameraController,
    scanIntervalMs: Long,
    onQrScanner: (String) -> Unit,
): ScannerHandle {
    CameraKLogger.d("QRScanner", "Starting QR scanner")
    val analyzer = controller.enableQrCodeScanner(scanIntervalMs, onQrScanner)
    return ScannerHandle { analyzer?.let { controller.unregisterImageAnalyzer(it) } }
}

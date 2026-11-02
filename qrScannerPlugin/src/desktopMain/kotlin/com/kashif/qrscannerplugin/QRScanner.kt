package com.kashif.qrscannerplugin

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.kashif.cameraK.utils.CameraKLogger
import java.awt.image.BufferedImage
import java.util.concurrent.locks.ReentrantLock

/**
 * Desktop implementation of QR code scanning using ZXing library.
 *
 * Processes BufferedImage frames to detect QR codes with thread-safe locking and a throttle on
 * decode attempts, so a scene with no code in it costs one decode per [scanIntervalMs].
 *
 * @param scanIntervalMs The shortest time between two decode attempts, found or not.
 */
class QRScanner internal constructor(
    private val clock: () -> Long,
    private val decoder: QrFrameDecoder,
    private val scanIntervalMs: Long = DEFAULT_SCAN_INTERVAL_MS,
) {
    constructor(scanIntervalMs: Long = DEFAULT_SCAN_INTERVAL_MS) : this(
        System::currentTimeMillis,
        ZxingQrFrameDecoder(),
        scanIntervalMs,
    )

    init {
        require(scanIntervalMs >= 0) { "scanIntervalMs must not be negative" }
    }

    private val lock = ReentrantLock()

    // Guarded by lock.
    private var lastAttemptTime = Long.MIN_VALUE / 2
    private var attempts = 0L

    // Guarded by lock. Decode failures other than "no readable code"; the first and then one in
    // LOG_FAILURE_EVERY are logged, so a decoder that fails on every attempt shows without flooding the log.
    private var unexpectedFailures = 0L

    /**
     * Scans a BufferedImage for QR codes.
     *
     * Uses thread-safe locking and decodes at most once per scan interval, whether or not the
     * last attempt found a code. Every fifth attempt also runs ZXing's slower TRY_HARDER pass.
     *
     * @param image The BufferedImage frame to scan
     * @return The decoded QR code text if successful, null if no code is found or processing is skipped
     */
    fun scanImage(image: BufferedImage): String? {
        if (!lock.tryLock()) return null

        return try {
            val currentTime = clock()
            if (currentTime - lastAttemptTime < scanIntervalMs) {
                return null
            }
            // The throttle counts attempts, not successes, so an empty scene is gated too.
            lastAttemptTime = currentTime
            attempts++

            try {
                decoder.decode(image, tryHarder = attempts % SLOW_PASS_EVERY == 0L)
            } catch (_: ReaderException) {
                // ZXing found no readable code in the frame: the normal case while scanning.
                null
            } catch (e: Exception) {
                if (unexpectedFailures++ % LOG_FAILURE_EVERY == 0L) {
                    CameraKLogger.e("QRScanner", "QR decode failed ($unexpectedFailures unexpected failures)", e)
                }
                null
            }
        } finally {
            lock.unlock()
        }
    }

    private companion object {
        const val DEFAULT_SCAN_INTERVAL_MS = 200L

        // ZXing's TRY_HARDER pass costs several fast passes; one attempt in this many uses it.
        const val SLOW_PASS_EVERY = 5L

        const val LOG_FAILURE_EVERY = 100L
    }
}

/** One ZXing decode of a frame, so a test can count and inspect attempts. */
internal fun interface QrFrameDecoder {
    /**
     * Returns the text of the QR code in [image].
     *
     * @param tryHarder Spend more time per frame looking for a hard-to-read code.
     * @throws NotFoundException when the frame holds no readable code.
     */
    fun decode(image: BufferedImage, tryHarder: Boolean): String
}

/** [QrFrameDecoder] over ZXing's [MultiFormatReader]. */
internal class ZxingQrFrameDecoder : QrFrameDecoder {
    private val fastReader = qrReader(tryHarder = false)
    private val slowReader = qrReader(tryHarder = true)

    override fun decode(image: BufferedImage, tryHarder: Boolean): String {
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val source = RGBLuminanceSource(image.width, image.height, pixels)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return (if (tryHarder) slowReader else fastReader).decode(bitmap).text
    }

    private fun qrReader(tryHarder: Boolean) = MultiFormatReader().apply {
        setHints(
            buildMap {
                put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
                if (tryHarder) put(DecodeHintType.TRY_HARDER, true)
            },
        )
    }
}

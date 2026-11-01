package com.kashif.qrscannerplugin

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.awt.image.BufferedImage
import java.util.concurrent.locks.ReentrantLock

/**
 * Desktop implementation of QR code scanning using ZXing library.
 *
 * Processes BufferedImage frames to detect QR codes with thread-safe locking
 * and frame rate throttling to optimize performance.
 */
class QRScanner internal constructor(private val clock: () -> Long, private val decoder: QrFrameDecoder) {
    constructor() : this(System::currentTimeMillis, ZxingQrFrameDecoder())

    private val lock = ReentrantLock()
    private var lastProcessTime = 0L
    private val processInterval = 200L

    /**
     * Scans a BufferedImage for QR codes.
     *
     * Uses thread-safe locking and throttles processing to every 200ms to improve
     * performance on resource-constrained systems.
     *
     * @param image The BufferedImage frame to scan
     * @return The decoded QR code text if successful, null if no code is found or processing is skipped
     */
    fun scanImage(image: BufferedImage): String? {
        if (!lock.tryLock()) return null

        return try {
            val currentTime = clock()
            if (currentTime - lastProcessTime < processInterval) {
                return null
            }

            try {
                val text = decoder.decode(image, tryHarder = true)
                lastProcessTime = currentTime
                text
            } catch (e: NotFoundException) {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            lock.unlock()
        }
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
    private val reader =
        MultiFormatReader().apply {
            val hints =
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                )
            setHints(hints)
        }

    override fun decode(image: BufferedImage, tryHarder: Boolean): String {
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val source = RGBLuminanceSource(image.width, image.height, pixels)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return reader.decode(bitmap).text
    }
}

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
class QRScanner {
    private val reader =
        MultiFormatReader().apply {
            val hints =
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                )
            setHints(hints)
        }
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
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastProcessTime < processInterval) {
                return null
            }

            val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            val source = RGBLuminanceSource(image.width, image.height, pixels)
            val bitmap = BinaryBitmap(HybridBinarizer(source))

            try {
                val result = reader.decode(bitmap)
                lastProcessTime = currentTime
                result.text
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

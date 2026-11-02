package com.kashif.qrscannerplugin

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QRScannerDesktopTest {
    private fun qrImage(payload: String, type: Int, dark: Int, light: Int): BufferedImage {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 240, 240)
        return BufferedImage(matrix.width, matrix.height, type).apply {
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) setRGB(x, y, if (matrix[x, y]) dark else light)
            }
        }
    }

    @Test
    fun decodesQrCodeFromRgbFrame() {
        val image = qrImage("camerak-desktop-qr", BufferedImage.TYPE_INT_RGB, 0x000000, 0xFFFFFF)

        assertEquals("camerak-desktop-qr", QRScanner().scanImage(image))
    }

    @Test
    fun decodesLowContrastColouredQrCodeFromBgrFrame() {
        val image = qrImage("https://example.com/a?b=1", BufferedImage.TYPE_3BYTE_BGR, 0x1F3A5F, 0xE8D8B0)

        assertEquals("https://example.com/a?b=1", QRScanner().scanImage(image))
    }

    @Test
    fun returnsNullForFrameWithoutQrCode() {
        val blank = BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB)

        assertNull(QRScanner().scanImage(blank))
    }
}

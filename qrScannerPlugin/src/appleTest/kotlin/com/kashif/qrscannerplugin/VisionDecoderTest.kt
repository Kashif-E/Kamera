@file:OptIn(ExperimentalForeignApi::class)

package com.kashif.qrscannerplugin

import com.kashif.cameraK.frames.FrameRect
import com.kashif.cameraK.frames.pixelBuffer
import com.kashif.cameraK.testing.TestFrame
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.coroutines.test.runTest
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetPixelFormatType
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.CoreVideo.kCVPixelFormatType_OneComponent8
import platform.ImageIO.kCGImagePropertyOrientationRight
import platform.Vision.VNBarcodeSymbologyEAN13
import platform.Vision.VNBarcodeSymbologyQR
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iOS decoder asks Vision for the configured symbologies only, inside the decode area, and
 * hands it a one-plane luma buffer built from the plugin's copy: never the frame's own pixel buffer.
 */
class VisionDecoderTest {
    @Test
    fun symbologiesAndRegionOfInterestAreSet() {
        val decoder = VisionBarcodeDecoder(setOf(BarcodeFormat.QR_CODE, BarcodeFormat.EAN_13, BarcodeFormat.UPC_A))
        val image = LumaImage(ByteArray(100 * 50), 100, 50, FrameRect(10, 5, 60, 45), rotationDegrees = 0)

        val request = decoder.request(image)

        assertEquals(setOf(VNBarcodeSymbologyQR, VNBarcodeSymbologyEAN13), request.symbologies.toSet(), "symbologies")
        // Vision's region is normalised, with its origin at the bottom left.
        request.regionOfInterest.useContents {
            val actual = listOf(origin.x, origin.y, size.width, size.height)
            val expected = listOf(0.1, 0.1, 0.5, 0.8)
            assertTrue(
                actual.zip(expected).all { (a, e) ->
                    abs(a - e) < 1e-9
                },
                "region of interest $actual, not $expected",
            )
        }
    }

    @Test
    fun aRotatedFrameIsReadUprightWithItsRegionOfInterestInTheUprightImage() {
        val decoder = VisionBarcodeDecoder(setOf(BarcodeFormat.QR_CODE))
        val image = LumaImage(ByteArray(100 * 50), 100, 50, FrameRect(10, 5, 60, 45), rotationDegrees = 90)

        val request = decoder.request(image)

        assertEquals(kCGImagePropertyOrientationRight, decoder.orientation(image), "orientation")
        // Upright, the frame is 50 x 100 and the area [5, 10, 45, 60).
        request.regionOfInterest.useContents {
            val actual = listOf(origin.x, origin.y, size.width, size.height)
            val expected = listOf(0.1, 0.4, 0.8, 0.5)
            assertTrue(
                actual.zip(expected).all { (a, e) ->
                    abs(a - e) < 1e-9
                },
                "region of interest $actual, not $expected",
            )
        }
    }

    @Test
    fun theLumaBufferIsOneEightBitPlaneHoldingTheLuma() {
        val luma = ByteArray(6 * 4) { (it * 7 + 3).toByte() }
        val image = LumaImage(luma, 6, 4, FrameRect(0, 0, 6, 4), rotationDegrees = 0)

        val buffer = assertNotNull(VisionBarcodeDecoder(setOf(BarcodeFormat.QR_CODE)).lumaPixelBuffer(image))
        try {
            assertEquals(kCVPixelFormatType_OneComponent8, CVPixelBufferGetPixelFormatType(buffer), "pixel format")
            assertEquals(6uL to 4uL, CVPixelBufferGetWidth(buffer) to CVPixelBufferGetHeight(buffer), "size")
            CVPixelBufferLockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly)
            val rowBytes = CVPixelBufferGetBytesPerRow(buffer).toInt()
            val base = assertNotNull(CVPixelBufferGetBaseAddress(buffer)).reinterpret<kotlinx.cinterop.ByteVar>()
            val copied = ByteArray(6 * 4) { base[(it / 6) * rowBytes + it % 6] }
            CVPixelBufferUnlockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly)
            assertEquals(luma.toList(), copied.toList())
        } finally {
            CVPixelBufferRelease(buffer)
        }
    }

    @Test
    fun aFrameWithoutAPixelBufferDecodes() = runTest {
        val frame = TestFrame.luma(8, 8, ByteArray(64))
        assertNull(frame.pixelBuffer, "a test frame's pixel buffer")
        val plugin = QRScannerPlugin(QRScannerConfig(), FakeDecoders { listOf(qr("HELLO")) })
        val results = collectResults(plugin)
        val s = qrSession(plugin)
        startReady(s)

        s.fake.fakeFrames.emit(frame)

        assertEquals(listOf("HELLO"), results.map { it.rawValue })
    }
}

package com.kashif.ocrPlugin

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
import platform.Foundation.NSNumber
import platform.Vision.VNRequestTextRecognitionLevelFast
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Live OCR on iOS reads frames at Vision's fast level, which is meant for real-time use, and takes
 * them in the camera's native 420f format, so no frame is colour-converted before Vision sees it.
 */
@OptIn(ExperimentalForeignApi::class)
class LiveOcrSetupTest {
    @Test
    fun liveFrames_areReadAtTheFastRecognitionLevel() {
        assertEquals(VNRequestTextRecognitionLevelFast, liveTextRequest { _, _ -> }.recognitionLevel)
    }

    @Test
    fun theOcrOutput_deliversNative420fFrames() {
        val settings = ocrVideoDataOutput().videoSettings

        assertEquals(
            kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
            (settings?.get("PixelFormatType") as? NSNumber)?.unsignedIntValue,
            "pixel format in $settings",
        )
    }
}

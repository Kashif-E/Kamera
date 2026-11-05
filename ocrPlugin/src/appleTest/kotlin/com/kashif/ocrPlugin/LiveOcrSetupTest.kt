package com.kashif.ocrPlugin

import com.kashif.cameraK.frames.pixelBuffer
import com.kashif.cameraK.utils.CameraKPlatformApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.rawValue
import kotlinx.coroutines.test.runTest
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetPixelFormatType
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelFormatType_OneComponent8
import platform.Vision.VNRequestTextRecognitionLevelAccurate
import platform.Vision.VNRequestTextRecognitionLevelFast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Live OCR on iOS reads frames at Vision's fast level unless configured otherwise, and hands Vision
 * a one-plane 8-bit pixel buffer of the frame's luma, never the camera's colour buffer, so it reads
 * a frame that has no platform pixel buffer at all.
 */
@OptIn(ExperimentalForeignApi::class, CameraKPlatformApi::class)
class LiveOcrSetupTest {
    @Test
    fun liveFrames_areReadAtTheFastRecognitionLevel() {
        assertEquals(VNRequestTextRecognitionLevelFast, liveTextRequest(OcrLevel.FAST) { _, _ -> }.recognitionLevel)
        assertEquals(
            VNRequestTextRecognitionLevelAccurate,
            liveTextRequest(OcrLevel.ACCURATE) { _, _ ->
            }.recognitionLevel,
        )
    }

    @Test
    fun theOcrOutput_deliversOneComponentLumaFromAFrameWithoutAPixelBuffer() = runTest {
        var seen: Seen? = null
        val plugin = OcrPlugin(
            OcrConfig { level = OcrLevel.ACCURATE },
            AppleTextRecognizers(
                livePerformer = { pixelBuffer, orientation, request ->
                    CVPixelBufferLockBaseAddress(pixelBuffer, 1u)
                    val base = CVPixelBufferGetBaseAddress(pixelBuffer)!!.rawValue
                    val rowBytes = CVPixelBufferGetBytesPerRow(pixelBuffer).toLong()
                    val width = CVPixelBufferGetWidth(pixelBuffer).toInt()
                    val height = CVPixelBufferGetHeight(pixelBuffer).toInt()
                    val luma = List(width * height) { i ->
                        interpretCPointer<ByteVar>(base + (i / width) * rowBytes + (i % width).toLong())!![0]
                    }
                    CVPixelBufferUnlockBaseAddress(pixelBuffer, 1u)
                    seen = Seen(
                        CVPixelBufferGetPixelFormatType(pixelBuffer),
                        width to height,
                        luma,
                        orientation,
                        request.recognitionLevel,
                    )
                    request.completionHandler?.invoke(request, null)
                    null
                },
            ),
        )
        val session = ocrSession(plugin)
        startReady(session)
        val frame = countingFrame(width = 5, height = 3, rotationDegrees = 90)

        session.engine.fakeFrames.emit(frame)

        assertNull(frame.pixelBuffer, "the test frame has a platform pixel buffer")
        val handed = checkNotNull(seen) { "Vision was handed nothing" }
        assertEquals(kCVPixelFormatType_OneComponent8, handed.format, "pixel format")
        assertEquals(5 to 3, handed.size, "size")
        assertEquals(List(15) { it.toByte() }, handed.luma, "luma rows")
        assertEquals(6u, handed.orientation, "orientation for a frame turned 90 degrees")
        assertEquals(VNRequestTextRecognitionLevelAccurate, handed.level, "level")
    }

    private class Seen(
        val format: UInt,
        val size: Pair<Int, Int>,
        val luma: List<Byte>,
        val orientation: UInt,
        val level: Long,
    )
}

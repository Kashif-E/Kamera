package com.kashif.analyzerPlugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame

/**
 * The desktop analyzer hands out an owned JPEG of each frame, as Android and iOS do: never the
 * frame's live pixel array, and whatever kind of raster the frame has.
 */
class DesktopAnalyzerFrameTest {

    @Test
    fun anIntBackedFrame_reachesTheAnalyzerAsAJpegOfTheFrame() {
        // The raster type mirrored frames used to have.
        val payload = analyze(BufferedImage(64, 48, BufferedImage.TYPE_INT_ARGB))

        assertDecodesToAnImageOf(64, 48, assertNotNull(payload, "the analyzer never received the int-backed frame"))
    }

    @Test
    fun aByteBackedFrame_reachesTheAnalyzerAsAnOwnedJpeg_notTheFramesPixelArray() {
        val frame = BufferedImage(64, 48, BufferedImage.TYPE_3BYTE_BGR)
        val payload = assertNotNull(analyze(frame), "the analyzer never received the frame")

        assertNotSame((frame.raster.dataBuffer as DataBufferByte).data, payload, "the payload is the frame's pixels")
        assertDecodesToAnImageOf(64, 48, payload)
    }

    private fun analyze(frame: BufferedImage): ByteArray? {
        val frames = MutableSharedFlow<BufferedImage>(replay = 1).apply { tryEmit(frame) }
        val payload = CompletableDeferred<ByteArray>()
        val handle = analyzeFrames(frames) { payload.complete(it) }
        return try {
            runBlocking { withTimeoutOrNull(5_000) { payload.await() } }
        } finally {
            handle.stop()
        }
    }

    private fun assertDecodesToAnImageOf(width: Int, height: Int, payload: ByteArray) {
        val image = assertNotNull(ImageIO.read(ByteArrayInputStream(payload)), "the payload is not an encoded image")
        assertEquals(width to height, image.width to image.height)
    }
}

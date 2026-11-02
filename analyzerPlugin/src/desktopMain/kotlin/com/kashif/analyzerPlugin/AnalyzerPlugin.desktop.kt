package com.kashif.analyzerPlugin

import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.awt.AlphaComposite
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO

/**
 * Starts the desktop analyzer. Each frame reaches [onFrameAvailable] as JPEG bytes the callback
 * owns, as on Android and iOS.
 */
actual fun startAnalyzer(cameraController: CameraController, onFrameAvailable: (ByteArray) -> Unit): AnalyzerHandle =
    analyzeFrames(cameraController.frameFlow, onFrameAvailable)

/** Hands each of [frames] to [onFrameAvailable] as an owned JPEG until the returned handle stops. */
internal fun analyzeFrames(frames: Flow<BufferedImage>, onFrameAvailable: (ByteArray) -> Unit): AnalyzerHandle {
    val scope = CoroutineScope(Dispatchers.Default)

    scope.launch {
        val encoder = JpegFrameEncoder()
        frames.collect { image ->
            val jpeg = try {
                encoder.encode(image)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CameraKLogger.e("CameraK", "Analyzer: Error encoding a frame; the frame is skipped", e)
                null
            }
            jpeg?.let(onFrameAvailable)
        }
    }

    // Without this the collector ran forever after detach (stopAnalyzer/onDetach had no effect).
    return AnalyzerHandle { scope.cancel() }
}

/**
 * Encodes frames as JPEG. The frame is first copied into a buffer this encoder owns, a quick blit,
 * so the producer can reuse the frame's image while the slower encode runs. JPEG has no alpha, so
 * the buffer is opaque BGR whatever the frame's raster type.
 */
private class JpegFrameEncoder {
    private var buffer: BufferedImage? = null

    fun encode(frame: BufferedImage): ByteArray {
        val copy = buffer?.takeIf { it.width == frame.width && it.height == frame.height }
            ?: BufferedImage(frame.width, frame.height, BufferedImage.TYPE_3BYTE_BGR).also { buffer = it }
        val graphics = copy.createGraphics()
        try {
            graphics.composite = AlphaComposite.Src
            graphics.drawImage(frame, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        val out = ByteArrayOutputStream(frame.width * frame.height / 4)
        if (!ImageIO.write(copy, "jpg", out)) throw IOException("No JPEG writer accepts the frame")
        return out.toByteArray()
    }
}

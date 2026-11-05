package com.kashif.analyzerPlugin

import android.graphics.Bitmap
import com.kashif.cameraK.frames.CameraFrame
import java.io.ByteArrayOutputStream

internal actual fun platformFrameEncoder(config: AnalyzerConfig): FrameEncoder = BitmapFrameEncoder(config)

/**
 * Encodes through an ARGB Bitmap and `Bitmap.compress`, from the frame's copied planes. The
 * frame-sized bitmap is reused while frames keep their size; the JPEG bytes are new for every frame.
 */
internal class BitmapFrameEncoder(private val config: AnalyzerConfig) : FrameEncoder {
    private val reader = RgbFrameReader()
    private var full: Bitmap? = null

    override fun encode(frame: CameraFrame): EncodedFrame? {
        val bitmap = full?.takeIf { it.width == frame.width && it.height == frame.height }
            ?: Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888).also {
                full?.recycle()
                full = it
            }
        bitmap.setPixels(reader.read(frame), 0, frame.width, 0, 0, frame.width, frame.height)
        val (width, height) = encodedSize(frame.width, frame.height, config.maxDimension)
        val encoded = if (width == frame.width && height == frame.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        try {
            val out = ByteArrayOutputStream(width * height / JPEG_SIZE_GUESS_DIVISOR)
            if (!encoded.compress(Bitmap.CompressFormat.JPEG, config.jpegQuality, out)) return null
            return EncodedFrame(out.toByteArray(), width, height, frame.rotationDegrees, frame.timestampNanos)
        } finally {
            if (encoded !== bitmap) encoded.recycle()
        }
    }

    private companion object {
        const val JPEG_SIZE_GUESS_DIVISOR = 4
    }
}

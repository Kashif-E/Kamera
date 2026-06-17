package com.kashif.cameraK.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

fun Context.getActivityOrNull(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }

    return null
}

/**
 * Compress bitmap to byte array with memory-efficient approach
 * @param format Bitmap format (JPEG, PNG)
 * @param quality Compression quality (0-100)
 * @param recycleInput Whether to recycle the input bitmap after compression
 * @return ByteArray containing the compressed image data
 */
fun Bitmap.compressToByteArray(
    format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
    quality: Int = 95,
    recycleInput: Boolean = false,
): ByteArray {
    val initialSize =
        when (format) {
            Bitmap.CompressFormat.JPEG -> width * height / 8
            Bitmap.CompressFormat.PNG -> width * height / 4
            else -> width * height / 6
        }

    val outputStream = ByteArrayOutputStream(initialSize.coerceIn(16384, 1024 * 1024))

    compress(format, quality.coerceIn(0, 100), outputStream)

    if (recycleInput) {
        recycle()
    }

    return outputStream.toByteArray()
}

/**
 * Encodes a YUV_420_888 [ImageProxy] frame to JPEG bytes.
 *
 * Analyzer consumers receive a decodable image (matching the iOS analyzer, which emits JPEG)
 * rather than raw YUV that `BitmapFactory` can't read.
 */
fun ImageProxy.toByteArray(quality: Int = 85): ByteArray {
    val nv21 = yuv420888ToNv21()
    val out = ByteArrayOutputStream()
    YuvImage(nv21, ImageFormat.NV21, width, height, null)
        .compressToJpeg(Rect(0, 0, width, height), quality, out)
    return out.toByteArray()
}

/**
 * Converts this YUV_420_888 frame to a contiguous NV21 (Y plane followed by interleaved V/U),
 * honoring row/pixel strides so padded planes don't corrupt the output.
 */
private fun ImageProxy.yuv420888ToNv21(): ByteArray {
    val ySize = width * height
    val nv21 = ByteArray(ySize + ySize / 2)

    // Duplicate so the bulk read below doesn't advance the shared plane buffer — the same
    // ImageProxy is handed to every registered analyzer, so mutating its position corrupts
    // the others. The strided/chroma reads use absolute get(index) and never mutate position.
    val yBuffer = planes[0].buffer.duplicate()
    val yRowStride = planes[0].rowStride
    val yPixelStride = planes[0].pixelStride
    var pos = 0
    if (yRowStride == width && yPixelStride == 1) {
        yBuffer.get(nv21, 0, ySize)
        pos = ySize
    } else {
        for (row in 0 until height) {
            val rowStart = row * yRowStride
            for (col in 0 until width) {
                nv21[pos++] = yBuffer.get(rowStart + col * yPixelStride)
            }
        }
    }

    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer
    val uRowStride = planes[1].rowStride
    val uPixelStride = planes[1].pixelStride
    val vRowStride = planes[2].rowStride
    val vPixelStride = planes[2].pixelStride
    val chromaWidth = width / 2
    val chromaHeight = height / 2
    for (row in 0 until chromaHeight) {
        val uRow = row * uRowStride
        val vRow = row * vRowStride
        for (col in 0 until chromaWidth) {
            nv21[pos++] = vBuffer.get(vRow + col * vPixelStride)
            nv21[pos++] = uBuffer.get(uRow + col * uPixelStride)
        }
    }
    return nv21
}

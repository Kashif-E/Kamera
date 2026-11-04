package com.kashif.cameraK.controller

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.kashif.cameraK.utils.CameraKLogger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val TAG = "CameraK"

/**
 * Turns the JPEG CameraX's file output writes into the PNG a PNG configuration asks for (§6.3). A
 * PNG from Bitmap.compress carries no EXIF orientation, so the pixels are turned by the JPEG's own
 * orientation first, and the result looks as the JPEG did in an EXIF-aware viewer. Decoding the
 * full image costs width × height × 4 bytes for the length of the call; it runs on the engine's
 * image-processing executor, never on the main thread.
 */
internal object PngTranscoder {
    /** Writes [jpeg] as a PNG into [png] and deletes [jpeg]. */
    fun transcode(jpeg: File, png: File) {
        val decoded = BitmapFactory.decodeFile(jpeg.path) ?: throw IOException("The captured JPEG does not decode")
        val oriented = oriented(decoded, exifOrientation(jpeg))
        try {
            val written = FileOutputStream(png).use { oriented.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!written) throw IOException("Encoding the PNG failed")
        } finally {
            if (oriented !== decoded) oriented.recycle()
            decoded.recycle()
        }
        if (!jpeg.delete()) CameraKLogger.w(TAG, "Deleting the JPEG a PNG capture was transcoded from failed")
    }

    private fun oriented(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> matrix.apply { setRotate(90f) }.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> matrix.apply { setRotate(-90f) }.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}

/** The EXIF orientation of [jpeg], or normal when it has none or cannot be read. */
internal fun exifOrientation(jpeg: File): Int = try {
    ExifInterface(jpeg.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
} catch (e: Exception) {
    // The framework's ExifInterface throws more than IOException on a malformed header.
    CameraKLogger.w(TAG, "Reading a capture's EXIF orientation failed", e)
    ExifInterface.ORIENTATION_NORMAL
}

/**
 * The upright size of the image in [file], read from its header: a JPEG whose EXIF orientation turns
 * it a quarter is reported turned, as a viewer shows it. Zero by zero when the file has no readable
 * image header.
 */
internal fun uprightSize(file: File, jpeg: Boolean): Pair<Int, Int> {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        CameraKLogger.w(TAG, "The captured image has no readable size")
        return 0 to 0
    }
    val quarterTurn =
        jpeg && exifOrientation(file) in ExifInterface.ORIENTATION_TRANSPOSE..ExifInterface.ORIENTATION_ROTATE_270
    return if (quarterTurn) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
}

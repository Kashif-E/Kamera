package com.kashif.cameraK.frames

/**
 * Writes the frame's luma, `width * height` bytes without row padding, into [destination] from
 * [destinationOffset], and returns the bytes written. For decoders that need only grayscale, so
 * they read either session format.
 *
 * YUV_420 copies plane 0 one row at a time, straight into [destination]. RGBA_8888 computes BT.601
 * luma one row at a time through [scratch], which must hold at least `width * 4` bytes. Pass a
 * scratch buffer, one per analyzer, to stay allocation-free: without one, or with one too small,
 * each call allocates one row.
 *
 * @throws IllegalArgumentException when [destination] holds fewer than `width * height` bytes from
 *   [destinationOffset], or plane 0 is not laid out as its format says (pixel stride 1 for YUV_420
 *   luma, 4 for RGBA_8888).
 */
public fun CameraFrame.copyLuma(destination: ByteArray, destinationOffset: Int = 0, scratch: ByteArray? = null): Int {
    val pixels = width * height
    require(destinationOffset >= 0 && destination.size - destinationOffset >= pixels) {
        "copyLuma needs $pixels bytes from offset $destinationOffset; the destination holds ${destination.size}"
    }
    val plane = planes[0]
    when (format) {
        FrameFormat.YUV_420 -> {
            require(plane.pixelStride == 1) { "YUV_420 luma with pixel stride ${plane.pixelStride}" }
            for (row in 0 until height) {
                plane.copyTo(destination, destinationOffset + row * width, row * plane.rowStride, width)
            }
        }
        FrameFormat.RGBA_8888 -> {
            require(plane.pixelStride == RGBA_BYTES_PER_PIXEL) { "RGBA_8888 with pixel stride ${plane.pixelStride}" }
            val rowBytes = width * RGBA_BYTES_PER_PIXEL
            val row = if (scratch != null && scratch.size >= rowBytes) scratch else ByteArray(rowBytes)
            for (y in 0 until height) {
                plane.copyTo(row, 0, y * plane.rowStride, rowBytes)
                var out = destinationOffset + y * width
                for (pixel in 0 until rowBytes step RGBA_BYTES_PER_PIXEL) {
                    val r = row[pixel].toInt() and 0xFF
                    val g = row[pixel + 1].toInt() and 0xFF
                    val b = row[pixel + 2].toInt() and 0xFF
                    destination[out++] =
                        ((BT601_R * r + BT601_G * g + BT601_B * b + ROUNDING) ushr FIXED_POINT_BITS).toByte()
                }
            }
        }
    }
    return pixels
}

private const val RGBA_BYTES_PER_PIXEL = 4

// BT.601 luma, 0.299 R + 0.587 G + 0.114 B, in 16-bit fixed point: the weights sum to 1 << 16,
// so white stays 255, and ROUNDING rounds to nearest.
private const val FIXED_POINT_BITS = 16
private const val BT601_R = 19595
private const val BT601_G = 38470
private const val BT601_B = 7471
private const val ROUNDING = 1 shl (FIXED_POINT_BITS - 1)

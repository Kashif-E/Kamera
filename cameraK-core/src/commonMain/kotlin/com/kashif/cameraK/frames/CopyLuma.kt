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
public fun CameraFrame.copyLuma(destination: ByteArray, destinationOffset: Int = 0, scratch: ByteArray? = null): Int =
    TODO("F0'")

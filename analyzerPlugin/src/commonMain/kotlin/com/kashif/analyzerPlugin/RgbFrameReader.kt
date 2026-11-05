package com.kashif.analyzerPlugin

import com.kashif.cameraK.frames.CameraFrame

/**
 * Reads a frame's colour into one ARGB int per pixel, row by row without padding. YUV_420 is read
 * as full-range BT.601 from its copied planes, whether [Y, U, V] (any pixel stride) or [Y, CbCr]
 * interleaved; a luma-only frame reads as grey. RGBA_8888 is read from its one plane. The plane
 * buffers and the pixel array are reused while frames keep their size.
 */
internal class RgbFrameReader {
    /** [frame]'s pixels; the array is this reader's and is overwritten by the next call. */
    fun read(frame: CameraFrame): IntArray = TODO("AN': RgbFrameReader.read")
}

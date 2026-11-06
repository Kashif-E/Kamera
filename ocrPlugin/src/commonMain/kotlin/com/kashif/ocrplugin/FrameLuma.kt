package com.kashif.ocrPlugin

import com.kashif.cameraK.frames.CameraFrame
import com.kashif.cameraK.frames.FrameRect
import com.kashif.cameraK.frames.copyLuma

/**
 * Reads the luma of the part of each frame live OCR recognizes, into buffers it reuses from frame
 * to frame. One per frame registration: the registration's worker is its only caller.
 */
internal class FrameLuma(private val region: FrameRect?) {
    private var frameLuma = ByteArray(0)
    private var regionLuma = ByteArray(0)
    private var rgbaRow = ByteArray(0)

    /**
     * The luma of [frame] inside [region] and the frame's `cropRect`, or null when they do not
     * overlap. The returned image's bytes are valid until the next call.
     */
    fun read(frame: CameraFrame): FrameArea? {
        val area = readArea(frame) ?: return null
        val pixels = frame.width * frame.height
        if (frameLuma.size < pixels) frameLuma = ByteArray(pixels)
        if (rgbaRow.size < frame.width * RGBA_BYTES_PER_PIXEL) rgbaRow = ByteArray(frame.width * RGBA_BYTES_PER_PIXEL)
        frame.copyLuma(frameLuma, 0, rgbaRow)
        val width = area.right - area.left
        val height = area.bottom - area.top
        val bytes =
            if (width == frame.width && height == frame.height) {
                frameLuma
            } else {
                if (regionLuma.size < width * height) regionLuma = ByteArray(width * height)
                for (row in 0 until height) {
                    val from = (area.top + row) * frame.width + area.left
                    frameLuma.copyInto(regionLuma, row * width, from, from + width)
                }
                regionLuma
            }
        return FrameArea(LumaImage(bytes, width, height, frame.rotationDegrees), area)
    }

    private fun readArea(frame: CameraFrame): FrameRect? {
        val crop = frame.cropRect
        val wanted = region ?: crop
        val left = maxOf(wanted.left, crop.left, 0)
        val top = maxOf(wanted.top, crop.top, 0)
        val right = minOf(wanted.right, crop.right, frame.width)
        val bottom = minOf(wanted.bottom, crop.bottom, frame.height)
        return if (right > left && bottom > top) FrameRect(left, top, right, bottom) else null
    }

    private companion object {
        const val RGBA_BYTES_PER_PIXEL = 4
    }
}

/** The luma a recognizer reads, and where it lies in the frame. */
internal class FrameArea(val image: LumaImage, private val area: FrameRect) {
    /** [upright], a rectangle in the upright image, in frame pixels. */
    fun toFrame(upright: FrameRect): FrameRect {
        val w = image.width
        val h = image.height
        val inArea =
            when (image.rotationDegrees) {
                90 -> FrameRect(upright.top, h - upright.right, upright.bottom, h - upright.left)
                180 -> FrameRect(w - upright.right, h - upright.bottom, w - upright.left, h - upright.top)
                270 -> FrameRect(w - upright.bottom, upright.left, w - upright.top, upright.right)
                else -> upright
            }
        return FrameRect(
            area.left + inArea.left.coerceIn(0, w),
            area.top + inArea.top.coerceIn(0, h),
            area.left + inArea.right.coerceIn(0, w),
            area.top + inArea.bottom.coerceIn(0, h),
        )
    }
}

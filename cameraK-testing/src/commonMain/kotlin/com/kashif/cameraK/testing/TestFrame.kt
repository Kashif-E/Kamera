package com.kashif.cameraK.testing

import com.kashif.cameraK.frames.CameraFrame
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FramePlane
import com.kashif.cameraK.frames.FrameRect
import com.kashif.cameraK.frames.RetainedFrame

/**
 * An analysis frame built from bytes, for tests: emit it through [FakeFrameSource.emit]. It keeps
 * its own copy of the bytes it was built from, and its planes copy out of that.
 */
public class TestFrame private constructor(
    override val width: Int,
    override val height: Int,
    override val format: FrameFormat,
    override val planes: List<FramePlane>,
    override val rotationDegrees: Int,
    override val timestampNanos: Long,
    override val cropRect: FrameRect,
) : CameraFrame {
    override fun retain(): RetainedFrame = TODO("F0'")

    public companion object {
        /**
         * A YUV_420 frame whose luma plane is [bytes], [rowStride] bytes per row (the last row may
         * stop after [width] bytes), and whose two chroma planes are neutral grey.
         */
        public fun luma(
            width: Int,
            height: Int,
            bytes: ByteArray,
            rowStride: Int = width,
            rotationDegrees: Int = 0,
            timestampNanos: Long = 0,
            cropRect: FrameRect? = null,
        ): TestFrame = TODO("F0'")

        /**
         * An RGBA_8888 frame: one plane holding R, G, B and A for each pixel, [rowStride] bytes per
         * row (the last row may stop after `width * 4` bytes).
         */
        public fun rgba(
            width: Int,
            height: Int,
            bytes: ByteArray,
            rowStride: Int = width * 4,
            rotationDegrees: Int = 0,
            timestampNanos: Long = 0,
            cropRect: FrameRect? = null,
        ): TestFrame = TODO("F0'")
    }
}

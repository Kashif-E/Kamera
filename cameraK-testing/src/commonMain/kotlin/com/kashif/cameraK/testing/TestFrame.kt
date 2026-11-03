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
    /** A test frame owns no platform buffer, so the retained frame is this frame, and closing it does nothing. */
    override fun retain(): RetainedFrame = Retained(this)

    private class Retained(frame: TestFrame) :
        RetainedFrame,
        CameraFrame by frame {
        override fun close() = Unit
    }

    private class ArrayPlane(
        private val bytes: ByteArray,
        override val rowStride: Int,
        override val pixelStride: Int,
    ) : FramePlane {
        override val size: Int get() = bytes.size

        override fun copyTo(destination: ByteArray, destinationOffset: Int): Int {
            bytes.copyInto(destination, destinationOffset)
            return bytes.size
        }

        override fun copyTo(destination: ByteArray, destinationOffset: Int, sourceOffset: Int, length: Int) {
            bytes.copyInto(destination, destinationOffset, sourceOffset, sourceOffset + length)
        }
    }

    public companion object {
        private const val NEUTRAL_CHROMA: Byte = 128.toByte()
        private const val RGBA_BYTES_PER_PIXEL = 4

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
        ): TestFrame {
            requireLayout(width, height, bytes, rowStride, rowBytes = width)
            val chromaWidth = (width + 1) / 2
            val chromaHeight = (height + 1) / 2
            fun chroma() = ArrayPlane(ByteArray(chromaWidth * chromaHeight) { NEUTRAL_CHROMA }, chromaWidth, 1)
            return TestFrame(
                width,
                height,
                FrameFormat.YUV_420,
                listOf(ArrayPlane(bytes.copyOf(), rowStride, 1), chroma(), chroma()),
                rotationDegrees,
                timestampNanos,
                cropRect ?: FrameRect(0, 0, width, height),
            )
        }

        /**
         * An RGBA_8888 frame: one plane holding R, G, B and A for each pixel, [rowStride] bytes per
         * row (the last row may stop after `width * 4` bytes).
         */
        public fun rgba(
            width: Int,
            height: Int,
            bytes: ByteArray,
            rowStride: Int = width * RGBA_BYTES_PER_PIXEL,
            rotationDegrees: Int = 0,
            timestampNanos: Long = 0,
            cropRect: FrameRect? = null,
        ): TestFrame {
            requireLayout(width, height, bytes, rowStride, rowBytes = width * RGBA_BYTES_PER_PIXEL)
            return TestFrame(
                width,
                height,
                FrameFormat.RGBA_8888,
                listOf(ArrayPlane(bytes.copyOf(), rowStride, RGBA_BYTES_PER_PIXEL)),
                rotationDegrees,
                timestampNanos,
                cropRect ?: FrameRect(0, 0, width, height),
            )
        }

        private fun requireLayout(width: Int, height: Int, bytes: ByteArray, rowStride: Int, rowBytes: Int) {
            require(width > 0 && height > 0) { "a frame needs a positive width and height, not $width x $height" }
            require(rowStride >= rowBytes) { "rowStride $rowStride is shorter than a row of $rowBytes bytes" }
            val needed = rowStride * (height - 1) + rowBytes
            require(bytes.size >= needed) {
                "$width x $height with rowStride $rowStride needs $needed bytes, not ${bytes.size}"
            }
        }
    }
}

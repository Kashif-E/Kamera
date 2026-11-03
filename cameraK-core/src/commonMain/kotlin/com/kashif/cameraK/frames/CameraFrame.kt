package com.kashif.cameraK.frames

/** One analysis frame. Its pixels are never rotated: [rotationDegrees] says how to turn it upright. */
public interface CameraFrame {
    public val width: Int
    public val height: Int

    /** Clockwise rotation to upright. */
    public val rotationDegrees: Int

    /** Capture clock, monotonic. */
    public val timestampNanos: Long
    public val format: FrameFormat

    /** Decode inside this. */
    public val cropRect: FrameRect

    /** YUV_420: [Y, U, V], or [Y, UV] interleaved (see [FramePlane.pixelStride]); plane 0 is always luma. */
    public val planes: List<FramePlane>

    /** For asynchronous consumers (ML Kit, Vision): the frame stays valid until the returned handle is closed. */
    public fun retain(): RetainedFrame
}

public interface FramePlane {
    public val rowStride: Int
    public val pixelStride: Int
    public val size: Int

    /** Bulk copy into a caller-owned, reusable buffer. Returns the bytes copied. */
    public fun copyTo(destination: ByteArray, destinationOffset: Int = 0): Int

    /**
     * Copies [length] bytes of this plane, starting at [sourceOffset], into [destination] at
     * [destinationOffset]. One row at a time, this reads a padded plane without its row padding
     * and without allocating.
     */
    public fun copyTo(destination: ByteArray, destinationOffset: Int, sourceOffset: Int, length: Int)
}

/** A frame kept past [FrameAnalyzer.analyze]; closing it gives the frame back. */
public interface RetainedFrame :
    CameraFrame,
    AutoCloseable

/** A rectangle in frame pixels, right and bottom exclusive. */
public class FrameRect(public val left: Int, public val top: Int, public val right: Int, public val bottom: Int)

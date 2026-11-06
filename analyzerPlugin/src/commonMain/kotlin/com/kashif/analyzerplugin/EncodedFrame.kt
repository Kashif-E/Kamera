package com.kashif.analyzerplugin

import dev.drewhamilton.poko.Poko

/**
 * One analysis frame, encoded as a JPEG the receiver owns. Its pixels are not rotated:
 * [rotationDegrees] says how to turn the image upright.
 *
 * @property bytes The JPEG.
 * @property width The JPEG's width in pixels, after any downscaling to `AnalyzerConfig.maxDimension`.
 * @property height The JPEG's height in pixels.
 * @property rotationDegrees Clockwise rotation to upright, the frame's own.
 * @property timestampNanos The frame's capture time, on the camera's monotonic clock.
 */
@Poko
public class EncodedFrame(
    @Poko.ReadArrayContent public val bytes: ByteArray,
    public val width: Int,
    public val height: Int,
    public val rotationDegrees: Int,
    public val timestampNanos: Long,
) {
    // The JPEG itself would flood a log line; its size says enough.
    override fun toString(): String = "EncodedFrame(bytes=${bytes.size} bytes, width=$width, height=$height, " +
        "rotationDegrees=$rotationDegrees, timestampNanos=$timestampNanos)"
}

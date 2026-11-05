package com.kashif.qrscannerplugin

import com.kashif.cameraK.frames.FrameRect

/**
 * One decoded barcode.
 *
 * The payload is attacker-controlled: anyone can print a code. Treat [rawValue] and [rawBytes] as
 * untrusted input; validate a URL before opening it, and never pass the payload to a shell, a query
 * or an intent unchecked. [toString] leaves the payload out, so logging a barcode does not log it.
 *
 * @property rawValue The payload as text, or null when it is not text.
 * @property rawBytes The payload's bytes as encoded (a QR code's byte segments), or null where the
 *   decoder does not expose them: on iOS, and for symbologies that carry text only.
 * @property format The symbology it was read as.
 * @property boundingBox Where it was found, in the frame's pixel coordinates (unrotated, like the
 *   frame), or null when the decoder reported no position.
 */
public class Barcode(
    public val rawValue: String?,
    public val rawBytes: ByteArray?,
    public val format: BarcodeFormat,
    public val boundingBox: FrameRect?,
) {
    override fun equals(other: Any?): Boolean = other is Barcode &&
        rawValue == other.rawValue &&
        rawBytes.contentEquals(other.rawBytes) &&
        format == other.format &&
        boundingBox.sameAs(other.boundingBox)

    override fun hashCode(): Int {
        var result = rawValue?.hashCode() ?: 0
        result = 31 * result + rawBytes.contentHashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + (boundingBox?.let { listOf(it.left, it.top, it.right, it.bottom).hashCode() } ?: 0)
        return result
    }

    override fun toString(): String = "Barcode(format=$format, rawValue length=${rawValue?.length}, " +
        "rawBytes size=${rawBytes?.size}, boundingBox=${boundingBox?.let {
            "[${it.left}, ${it.top}, ${it.right}, ${it.bottom})"
        }})"
}

private fun FrameRect?.sameAs(other: FrameRect?): Boolean = if (this == null || other == null) {
    this === other
} else {
    left == other.left && top == other.top && right == other.right && bottom == other.bottom
}

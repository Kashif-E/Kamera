package com.kashif.cameraK.enums

/**
 * Enum representing the image format for captured photos.
 *
 * Android captures are always JPEG (CameraX encodes nothing else): with [PNG] the controller logs a
 * warning and writes a `.jpg` registered as `image/jpeg`.
 */
enum class ImageFormat(val extension: String, val mimeType: String) {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),

    // S1' stub: a placeholder extension and MIME type.
    HEIC("", ""),
}

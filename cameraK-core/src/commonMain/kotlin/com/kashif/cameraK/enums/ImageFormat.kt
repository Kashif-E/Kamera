package com.kashif.cameraK.enums

/**
 * Closed: the image format for captured photos. A new format waits for a major release.
 *
 * Android captures are always JPEG (CameraX encodes nothing else): with [PNG] the controller logs a
 * warning and writes a `.jpg` registered as `image/jpeg`. [HEIC] is not written on any platform yet:
 * a capture configured as HEIC fails with an [com.kashif.cameraK.result.ImageCaptureResult.Error]
 * and writes nothing.
 */
enum class ImageFormat(val extension: String, val mimeType: String) {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    HEIC("heic", "image/heic"),
}

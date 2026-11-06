package com.kashif.cameraK.enums

/**
 * Closed: the image format for captured photos. A new format waits for a major release.
 *
 * CameraX encodes only JPEG, so an Android capture configured as [PNG] is transcoded to PNG after
 * it is written. [HEIC] is written only on iOS, where the photo output can encode HEVC; elsewhere a
 * capture or start configured as HEIC fails with `Unsupported(IMAGE_FORMAT)` and writes nothing.
 */
enum class ImageFormat(val extension: String, val mimeType: String) {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    HEIC("heic", "image/heic"),
}

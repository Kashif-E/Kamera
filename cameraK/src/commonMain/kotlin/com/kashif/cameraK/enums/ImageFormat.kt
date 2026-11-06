package com.kashif.cameraK.enums

/**
 * Enum representing the image format for captured photos.
 *
 * [PNG] is honored by `takePictureToFile()` on iOS and Desktop only. On Android, CameraX encodes
 * stills as JPEG, so the file gets a `.png` name but contains JPEG data (a warning is logged when the
 * controller is created). Use [JPEG] on Android and convert in app code if PNG is required.
 */
enum class ImageFormat(val extension: String, val mimeType: String) {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
}

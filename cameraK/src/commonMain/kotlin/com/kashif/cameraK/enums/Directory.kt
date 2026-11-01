package com.kashif.cameraK.enums

/**
 * Where captured images are stored. The same value means different places for the camera
 * controller and for ImageSaverPlugin.
 *
 * Camera controller (`takePictureToFile()`):
 * - PICTURES: Android inserts the photo into MediaStore under `Pictures/CameraK` and returns its
 *   `content://` URI as `ImageCaptureResult.SuccessWithUri`; iOS saves to the Photos library
 * - DCIM: like PICTURES, under `DCIM/CameraK` on Android
 * - DOCUMENTS: Saves to the app's own storage, not shared with other apps or synced with Photos
 *   (Android: the app-specific external files directory; iOS: the app's Documents directory)
 * - Desktop: PICTURES and DCIM save to `~/Pictures/CameraK`, DOCUMENTS to `~/Documents/CameraK`,
 *   readable only by the user where the file system has POSIX permissions
 *
 * ImageSaverPlugin:
 * - PICTURES and DCIM: Android saves to the public `Pictures/` or `DCIM/` directory, desktop to
 *   `~/Pictures`, iOS to the Photos library
 * - DOCUMENTS: Android saves to the public, shared `Documents/` directory through `MediaStore.Files`,
 *   where other apps can see it; desktop saves to `~/Documents`; iOS saves to the Photos library
 * - Android puts the image in a `customFolderName` subfolder, `CameraK` by default; desktop uses
 *   `customFolderName` only when it is set; iOS ignores it.
 */
enum class Directory {
    PICTURES,
    DCIM,
    DOCUMENTS,
}

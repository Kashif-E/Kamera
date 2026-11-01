package com.kashif.cameraK.enums

/**
 * Where captured images are stored. The same value means different places for the camera
 * controller and for ImageSaverPlugin.
 *
 * Camera controller (`takePictureToFile()`):
 * - PICTURES: Android saves to the public `Pictures/CameraK` directory, iOS saves to the Photos library
 * - DCIM: Android saves to the public `DCIM/CameraK` directory, iOS saves to the Photos library
 * - DOCUMENTS: Saves to the app's own storage, not shared with other apps or synced with Photos
 *   (Android: the app-specific external files directory; iOS: the app's Documents directory)
 * - Desktop writes a temporary file whatever the value.
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

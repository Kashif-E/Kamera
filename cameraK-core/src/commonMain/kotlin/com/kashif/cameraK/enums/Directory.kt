package com.kashif.cameraK.enums

/**
 * Where captured images are stored. The same value means different places for the camera
 * controller and for ImageSaverPlugin.
 *
 * Camera (`capture()`, `takePictureToFile()`, recordings, `CameraConfiguration.directory`): every
 * capture and recording is an app-owned file, whatever the value. It goes to
 * `CameraConfiguration.outputDirectory` when set, else to `filesDir/CameraK` on Android,
 * `Documents/CameraK` on iOS and `~/Documents/CameraK` on desktop. PICTURES and DCIM are ignored, with
 * one logged warning: the core never inserts into MediaStore, runs the media scanner or imports into
 * Photos. To publish a capture to the gallery, save it with ImageSaverPlugin.
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

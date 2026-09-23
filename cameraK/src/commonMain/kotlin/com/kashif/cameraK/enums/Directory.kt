package com.kashif.cameraK.enums

/**
 * Enum representing the directory where captured images will be saved.
 *
 * - PICTURES: Android saves to public Pictures directory, iOS saves to Photos library
 * - DCIM: Android saves to public DCIM directory, iOS saves to Photos library
 * - DOCUMENTS: Saves to app-specific storage, private to the app. Captures are not added to Photos or
 *   announced to Android's media scanner. Both platforms back this storage up by default (iCloud for iOS
 *   `Documents/`, Auto Backup for Android `getExternalFilesDir()`); exclude the `CameraK` folder in your
 *   app if captures must not leave the device.
 */
enum class Directory {
    PICTURES,
    DCIM,
    DOCUMENTS,
}

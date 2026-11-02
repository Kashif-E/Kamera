package com.kashif.cameraK.permissions

/** Closed: a runtime permission CameraK or its plugins need. A new permission waits for a major release. */
public enum class CameraKPermission {
    CAMERA,
    MICROPHONE,

    /** Adding photos and videos to the gallery; the media saver plugin's. */
    GALLERY_ADD,
}

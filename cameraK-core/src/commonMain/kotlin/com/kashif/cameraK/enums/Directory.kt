package com.kashif.cameraK.enums

/**
 * The 1.x choice of where captures went. 2.0 reads it nowhere: every capture and recording is an
 * app-owned file, in `CameraConfiguration.outputDirectory` when set, and MediaSaverPlugin publishes
 * to the gallery through its `GalleryDestination`. It remains only as the type of the 1.x
 * named-argument `CameraConfiguration(...)` hint's `directory` parameter, and goes with that hint in
 * 2.1.
 */
@Deprecated(
    "Removed in CameraK 2.0: captures are app-owned files (CameraConfiguration.outputDirectory), and " +
        "MediaSaverPlugin publishes to the gallery through its GalleryDestination.",
    level = DeprecationLevel.ERROR,
)
enum class Directory {
    PICTURES,
    DCIM,
    DOCUMENTS,
}

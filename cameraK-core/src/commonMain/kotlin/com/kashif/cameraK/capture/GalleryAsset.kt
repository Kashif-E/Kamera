package com.kashif.cameraK.capture

/**
 * An entry the media saver plugin added to the photo gallery: an opaque, typed handle. Apps read it
 * through the platform accessors (`uri` on Android, `localIdentifier` on iOS, `path` on desktop) and
 * never build one. Compares by value.
 *
 * @property displayName The name the gallery shows.
 */
public class GalleryAsset internal constructor(
    public val displayName: String,
    // An Android Uri, an iOS Photos local identifier or a desktop file path, as the platform factory
    // in this module's platform source set stored it.
    internal val payload: Any,
)

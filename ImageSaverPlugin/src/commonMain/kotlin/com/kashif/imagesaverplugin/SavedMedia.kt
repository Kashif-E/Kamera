package com.kashif.imagesaverplugin

import com.kashif.cameraK.capture.GalleryAsset

/**
 * A still the saver added to the gallery.
 *
 * @property displayName The name the gallery shows.
 * @property asset The gallery's handle: `uri` on Android, `localIdentifier` on iOS, `path` on desktop.
 */
public class SavedImage internal constructor(public val displayName: String, public val asset: GalleryAsset) {
    override fun equals(other: Any?): Boolean = other is SavedImage && other.asset == asset

    override fun hashCode(): Int = asset.hashCode()

    override fun toString(): String = "SavedImage"
}

/**
 * A recording the saver added to the gallery.
 *
 * @property displayName The name the gallery shows.
 * @property asset The gallery's handle: `uri` on Android, `localIdentifier` on iOS, `path` on desktop.
 */
public class SavedVideo internal constructor(public val displayName: String, public val asset: GalleryAsset) {
    override fun equals(other: Any?): Boolean = other is SavedVideo && other.asset == asset

    override fun hashCode(): Int = asset.hashCode()

    override fun toString(): String = "SavedVideo"
}

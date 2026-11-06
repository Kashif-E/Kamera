package com.kashif.imagesaverplugin

import androidx.compose.runtime.Composable
import com.kashif.mediasaverplugin.MediaSaverPlugin

// CameraK 2.0 moved the saver to com.kashif.mediasaverplugin, in the artifact camerak-media-saver. Each
// old name is an IDE hint to the new one and goes in 2.1 (design §9.4, §10.4).

/** Removed in 2.0: the saver is [MediaSaverPlugin]. */
@Deprecated(
    "Removed in CameraK 2.0: the saver is com.kashif.mediasaverplugin.MediaSaverPlugin.",
    ReplaceWith("MediaSaverPlugin", "com.kashif.mediasaverplugin.MediaSaverPlugin"),
    level = DeprecationLevel.ERROR,
)
public typealias ImageSaverPlugin = MediaSaverPlugin

/** Removed in 2.0: moved to `com.kashif.mediasaverplugin`. */
@Deprecated(
    "Removed in CameraK 2.0: moved to com.kashif.mediasaverplugin.",
    ReplaceWith("MediaSaverConfig", "com.kashif.mediasaverplugin.MediaSaverConfig"),
    level = DeprecationLevel.ERROR,
)
public typealias MediaSaverConfig = com.kashif.mediasaverplugin.MediaSaverConfig

/** Removed in 2.0: moved to `com.kashif.mediasaverplugin`. */
@Deprecated(
    "Removed in CameraK 2.0: moved to com.kashif.mediasaverplugin.",
    ReplaceWith("GalleryDestination", "com.kashif.mediasaverplugin.GalleryDestination"),
    level = DeprecationLevel.ERROR,
)
public typealias GalleryDestination = com.kashif.mediasaverplugin.GalleryDestination

/** Removed in 2.0: moved to `com.kashif.mediasaverplugin`. */
@Deprecated(
    "Removed in CameraK 2.0: moved to com.kashif.mediasaverplugin.",
    ReplaceWith("SavedImage", "com.kashif.mediasaverplugin.SavedImage"),
    level = DeprecationLevel.ERROR,
)
public typealias SavedImage = com.kashif.mediasaverplugin.SavedImage

/** Removed in 2.0: moved to `com.kashif.mediasaverplugin`. */
@Deprecated(
    "Removed in CameraK 2.0: moved to com.kashif.mediasaverplugin.",
    ReplaceWith("SavedVideo", "com.kashif.mediasaverplugin.SavedVideo"),
    level = DeprecationLevel.ERROR,
)
public typealias SavedVideo = com.kashif.mediasaverplugin.SavedVideo

/** Removed in 2.0: renamed with the class. */
@Deprecated(
    "Removed in CameraK 2.0: use com.kashif.mediasaverplugin.rememberMediaSaverPlugin.",
    ReplaceWith("rememberMediaSaverPlugin(config)", "com.kashif.mediasaverplugin.rememberMediaSaverPlugin"),
    level = DeprecationLevel.ERROR,
)
@Suppress("UNUSED_PARAMETER")
@Composable
public fun rememberImageSaverPlugin(config: com.kashif.mediasaverplugin.MediaSaverConfig): MediaSaverPlugin =
    throw UnsupportedOperationException("Removed in CameraK 2.0; see the migration guide")

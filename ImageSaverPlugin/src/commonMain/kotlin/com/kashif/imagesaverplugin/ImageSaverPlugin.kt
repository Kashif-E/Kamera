package com.kashif.imagesaverplugin

import androidx.compose.runtime.Composable
import dev.kashifmehmood.camerak.mediasaver.MediaSaverPlugin

// CameraK 2.0 moved the saver to dev.kashifmehmood.camerak.mediasaver, in the artifact camerak-media-saver. Each
// old name is an IDE hint to the new one and goes in 2.1 (design §9.4, §10.4).

/** Removed in 2.0: the saver is [MediaSaverPlugin]. */
@Deprecated(
    "Removed in CameraK 2.0: the saver is dev.kashifmehmood.camerak.mediasaver.MediaSaverPlugin.",
    ReplaceWith("MediaSaverPlugin", "dev.kashifmehmood.camerak.mediasaver.MediaSaverPlugin"),
    level = DeprecationLevel.ERROR,
)
public typealias ImageSaverPlugin = MediaSaverPlugin

/** Removed in 2.0: moved to `dev.kashifmehmood.camerak.mediasaver`. */
@Deprecated(
    "Removed in CameraK 2.0: moved to dev.kashifmehmood.camerak.mediasaver.",
    ReplaceWith("MediaSaverConfig", "dev.kashifmehmood.camerak.mediasaver.MediaSaverConfig"),
    level = DeprecationLevel.ERROR,
)
public typealias MediaSaverConfig = dev.kashifmehmood.camerak.mediasaver.MediaSaverConfig

/** Removed in 2.0: moved to `dev.kashifmehmood.camerak.mediasaver`. */
@Deprecated(
    "Removed in CameraK 2.0: moved to dev.kashifmehmood.camerak.mediasaver.",
    ReplaceWith("GalleryDestination", "dev.kashifmehmood.camerak.mediasaver.GalleryDestination"),
    level = DeprecationLevel.ERROR,
)
public typealias GalleryDestination = dev.kashifmehmood.camerak.mediasaver.GalleryDestination

/** Removed in 2.0: moved to `dev.kashifmehmood.camerak.mediasaver`. */
@Deprecated(
    "Removed in CameraK 2.0: moved to dev.kashifmehmood.camerak.mediasaver.",
    ReplaceWith("SavedImage", "dev.kashifmehmood.camerak.mediasaver.SavedImage"),
    level = DeprecationLevel.ERROR,
)
public typealias SavedImage = dev.kashifmehmood.camerak.mediasaver.SavedImage

/** Removed in 2.0: moved to `dev.kashifmehmood.camerak.mediasaver`. */
@Deprecated(
    "Removed in CameraK 2.0: moved to dev.kashifmehmood.camerak.mediasaver.",
    ReplaceWith("SavedVideo", "dev.kashifmehmood.camerak.mediasaver.SavedVideo"),
    level = DeprecationLevel.ERROR,
)
public typealias SavedVideo = dev.kashifmehmood.camerak.mediasaver.SavedVideo

/** Removed in 2.0: renamed with the class. */
@Deprecated(
    "Removed in CameraK 2.0: use dev.kashifmehmood.camerak.mediasaver.rememberMediaSaverPlugin.",
    ReplaceWith("rememberMediaSaverPlugin(config)", "dev.kashifmehmood.camerak.mediasaver.rememberMediaSaverPlugin"),
    level = DeprecationLevel.ERROR,
)
@Suppress("UNUSED_PARAMETER")
@Composable
public fun rememberImageSaverPlugin(config: dev.kashifmehmood.camerak.mediasaver.MediaSaverConfig): MediaSaverPlugin =
    throw UnsupportedOperationException("Removed in CameraK 2.0; see the migration guide")

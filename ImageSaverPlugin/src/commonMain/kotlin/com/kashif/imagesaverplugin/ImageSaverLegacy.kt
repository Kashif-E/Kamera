@file:Suppress("DEPRECATION")

package com.kashif.imagesaverplugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.platform.CameraKPlatformContext

// The 1.x saver API, kept deprecated until C1' removes it. This is the saver's only file that names
// core's Directory; check_plugin_sources.py holds every other saver source to that.

/**
 * The 1.x configuration. [directory] maps to the [GalleryDestination] of the same name.
 *
 * @param isAutoSave Whether captures are saved automatically.
 * @param prefix Auto-save names are `<prefix>_<epochMillis>`; at most 50 characters from `A-Z a-z 0-9 . _ -`.
 * @param directory Where saves go.
 * @param customFolderName The folder inside [directory]; `CameraK` when null.
 * @param imageFormat The format of a byte save.
 */
@Deprecated(
    "Use MediaSaverConfig { autoSave = …; prefix = …; destination = …; folderName = …; imageFormat = … }.",
    level = DeprecationLevel.WARNING,
)
@Immutable
public data class ImageSaverConfig(
    val isAutoSave: Boolean = false,
    val prefix: String? = null,
    val directory: Directory = Directory.PICTURES,
    val customFolderName: String? = null,
    val imageFormat: ImageFormat = ImageFormat.JPEG,
)

/** Outcome of the deprecated byte save. */
@Deprecated("save(image) returns a CameraKResult<SavedImage>.", level = DeprecationLevel.WARNING)
@Immutable
public sealed class ImageSaveResult {
    /**
     * @property location Where the image was stored: a `content://` URI on Android, a file path on
     *   desktop, a `ph://` location on iOS.
     */
    @Immutable
    public data class Saved(val location: String) : ImageSaveResult()

    /** @property error Why the image was not stored. A platform exception's message may contain paths. */
    @Immutable
    public data class Failed(val error: Throwable) : ImageSaveResult()
}

/** The destination of the same name. */
internal fun Directory.toGalleryDestination(): GalleryDestination = when (this) {
    Directory.PICTURES -> GalleryDestination.PICTURES
    Directory.DCIM -> GalleryDestination.DCIM
    Directory.DOCUMENTS -> GalleryDestination.DOCUMENTS
}

/** The same settings as a [MediaSaverConfig]; an invalid prefix or folder name throws as that DSL does. */
internal fun ImageSaverConfig.toMediaSaverConfig(): MediaSaverConfig = MediaSaverConfig {
    autoSave = isAutoSave
    prefix = this@toMediaSaverConfig.prefix
    destination = directory.toGalleryDestination()
    folderName = customFolderName
    imageFormat = this@toMediaSaverConfig.imageFormat
}

/** The 1.x entry point, over the [MediaSaverConfig] [config] maps to. */
@Deprecated(
    "Pass a MediaSaverConfig: rememberImageSaverPlugin(MediaSaverConfig { … }).",
    level = DeprecationLevel.WARNING,
)
@Composable
public fun rememberImageSaverPlugin(
    config: ImageSaverConfig,
    platformContext: CameraKPlatformContext = rememberCameraKPlatformContext(),
): ImageSaverPlugin = rememberImageSaverPlugin(config.toMediaSaverConfig(), platformContext)

package org.company.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.mediasaverplugin.GalleryDestination
import com.kashif.mediasaverplugin.MediaSaverConfig
import com.kashif.mediasaverplugin.MediaSaverPlugin
import com.kashif.mediasaverplugin.rememberMediaSaverPlugin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * The Sample's image saver: with [MediaSaverConfig.autoSave] on, every capture that reaches the
 * holder's captures goes to the gallery.
 */
internal class ImageSaverFeature(val plugin: MediaSaverPlugin) {
    /** One line per gallery save that failed, for the screen to show. */
    val notices: Flow<String> = galleryNotices(plugin.results)
}

/** A line for each failed save among [results]; a save that worked needs none past "Photo taken". */
internal fun galleryNotices(results: Flow<CameraKResult<*>>): Flow<String> = results.mapNotNull { result ->
    when ((result as? CameraKResult.Failure)?.error) {
        null -> null
        is CameraKError.Permission -> "Allow access to Photos to save to the gallery"
        else -> "Could not save the photo to the gallery"
    }
}

/** The saver, saving to the gallery when [saveToGallery]; a change is a new plugin, which the holder swaps in. */
@Composable
internal fun rememberImageSaverFeature(saveToGallery: Boolean = true): ImageSaverFeature {
    val config = remember(saveToGallery) {
        MediaSaverConfig {
            autoSave = saveToGallery
            prefix = "CameraK"
            destination = GalleryDestination.PICTURES
            folderName = "CameraK"
        }
    }
    val plugin = rememberMediaSaverPlugin(config)
    return remember(plugin) { ImageSaverFeature(plugin) }
}

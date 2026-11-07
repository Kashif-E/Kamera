package org.company.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kashif.mediasaverplugin.GalleryDestination
import com.kashif.mediasaverplugin.MediaSaverConfig
import com.kashif.mediasaverplugin.MediaSaverPlugin
import com.kashif.mediasaverplugin.rememberMediaSaverPlugin

/**
 * The Sample's image saver: with [MediaSaverConfig.autoSave] on, every capture that reaches the
 * holder's captures goes to the gallery.
 */
internal class ImageSaverFeature(val plugin: MediaSaverPlugin)

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

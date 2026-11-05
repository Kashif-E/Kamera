package org.company.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kashif.imagesaverplugin.GalleryDestination
import com.kashif.imagesaverplugin.ImageSaverPlugin
import com.kashif.imagesaverplugin.MediaSaverConfig
import com.kashif.imagesaverplugin.rememberImageSaverPlugin

/** The Sample's image saver: every capture that reaches the holder's captures goes to the gallery. */
internal class ImageSaverFeature(val plugin: ImageSaverPlugin)

@Composable
internal fun rememberImageSaverFeature(): ImageSaverFeature {
    val config = remember {
        MediaSaverConfig {
            autoSave = true
            prefix = "CameraK"
            destination = GalleryDestination.PICTURES
            folderName = "CameraK"
        }
    }
    val plugin = rememberImageSaverPlugin(config)
    return remember(plugin) { ImageSaverFeature(plugin) }
}

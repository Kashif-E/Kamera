package org.company.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kashif.mediasaverplugin.GalleryDestination
import com.kashif.mediasaverplugin.MediaSaverConfig
import com.kashif.mediasaverplugin.MediaSaverPlugin
import com.kashif.mediasaverplugin.rememberMediaSaverPlugin

/** The Sample's image saver: every capture that reaches the holder's captures goes to the gallery. */
internal class ImageSaverFeature(val plugin: MediaSaverPlugin)

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
    val plugin = rememberMediaSaverPlugin(config)
    return remember(plugin) { ImageSaverFeature(plugin) }
}

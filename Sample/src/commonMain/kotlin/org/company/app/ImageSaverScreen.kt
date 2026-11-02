package org.company.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kashif.cameraK.enums.Directory
import com.kashif.imagesaverplugin.ImageSaverConfig
import com.kashif.imagesaverplugin.ImageSaverPlugin
import com.kashif.imagesaverplugin.rememberImageSaverPlugin

/** The Sample's image saver: every capture goes to the gallery automatically. */
internal class ImageSaverFeature(val plugin: ImageSaverPlugin)

@Composable
internal fun rememberImageSaverFeature(): ImageSaverFeature {
    val plugin = rememberImageSaverPlugin(
        config = ImageSaverConfig(
            isAutoSave = true,
            prefix = "CameraK",
            directory = Directory.PICTURES,
            customFolderName = "CameraK",
        ),
    )
    return remember(plugin) { ImageSaverFeature(plugin) }
}

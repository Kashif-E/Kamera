@file:Suppress("DEPRECATION")

package com.kashif.imagesaverplugin

import com.kashif.cameraK.permissions.PermissionStatus
import java.io.File

/** A saver over [home] built from the deprecated [config], for the tests that carry the 1.x byte API. */
internal fun legacySaver(config: ImageSaverConfig, home: File): ImageSaverPlugin {
    val mapped = config.toMediaSaverConfig()
    return ImageSaverPlugin(mapped, DesktopGalleryStore(mapped, home), { PermissionStatus.Granted })
}

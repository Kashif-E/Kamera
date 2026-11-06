package com.kashif.imagesaverplugin

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.testing.FakeCaptures
import java.io.File
import java.nio.file.Files

/** A saver over [home] for [config]; desktop needs no gallery permission. */
internal fun desktopSaver(home: File, config: MediaSaverConfig = MediaSaverConfig()): ImageSaverPlugin =
    ImageSaverPlugin(config, DesktopGalleryStore(config, home), { PermissionStatus.Granted })

/**
 * A capture whose app-owned file holds [bytes], in a folder of its own outside any saver's home, as
 * CameraK's own captures are before a saver copies them.
 */
internal fun capturedImage(bytes: ByteArray, format: ImageFormat = ImageFormat.JPEG): CapturedImage {
    val folder = Files.createTempDirectory("camerak-capture").toFile().apply { deleteOnExit() }
    val file = File(folder, "IMG_0001.${format.extension}").apply {
        writeBytes(bytes)
        deleteOnExit()
    }
    return FakeCaptures.image(path = file.path, format = format)
}

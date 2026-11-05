@file:Suppress("DEPRECATION")

package com.kashif.imagesaverplugin

import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The deprecated ImageSaverConfig maps to MediaSaverConfig until C1' removes it; its directory to the destination of the same name. */
class LegacyConfigMappingTest {
    @Test
    fun picturesMapsToPictures() {
        assertEquals(
            GalleryDestination.PICTURES,
            ImageSaverConfig(directory = Directory.PICTURES).toMediaSaverConfig().destination,
        )
    }

    @Test
    fun dcimMapsToDcim() {
        assertEquals(
            GalleryDestination.DCIM,
            ImageSaverConfig(directory = Directory.DCIM).toMediaSaverConfig().destination,
        )
    }

    @Test
    fun documentsMapsToDocuments() {
        assertEquals(
            GalleryDestination.DOCUMENTS,
            ImageSaverConfig(directory = Directory.DOCUMENTS).toMediaSaverConfig().destination,
        )
    }

    @Test
    fun everyFieldMaps() {
        val legacy = ImageSaverConfig(
            isAutoSave = true,
            prefix = "Shot",
            directory = Directory.DCIM,
            customFolderName = "Trip",
            imageFormat = ImageFormat.PNG,
        )

        assertEquals(
            MediaSaverConfig {
                autoSave = true
                prefix = "Shot"
                destination = GalleryDestination.DCIM
                folderName = "Trip"
                imageFormat = ImageFormat.PNG
            },
            legacy.toMediaSaverConfig(),
        )
    }

    @Test
    fun anInvalidLegacyPrefixIsRejectedAsTheDslRejectsIt() {
        assertFailsWith<IllegalArgumentException> { ImageSaverConfig(prefix = "../x").toMediaSaverConfig() }
    }
}

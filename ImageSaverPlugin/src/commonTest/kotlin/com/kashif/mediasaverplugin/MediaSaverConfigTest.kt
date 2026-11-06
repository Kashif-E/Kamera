package com.kashif.mediasaverplugin

import com.kashif.cameraK.enums.ImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class MediaSaverConfigTest {
    @Test
    fun defaultsArePicturesCameraKJpegWithoutAutoSave() {
        val config = MediaSaverConfig()

        assertFalse(config.autoSave)
        assertNull(config.prefix)
        assertEquals(GalleryDestination.PICTURES, config.destination)
        assertNull(config.folderName, "a null folder name is the CameraK folder")
        assertEquals(ImageFormat.JPEG, config.imageFormat)
    }

    @Test
    fun invalidPrefixOrFolderNameIsRejected() {
        val badPrefixes = listOf("p".repeat(51), "../escaped", "nested/shot", "line\nbreak")
        for (prefix in badPrefixes) {
            val error = assertFailsWith<IllegalArgumentException>("prefix ${prefix.length} chars") {
                MediaSaverConfig { this.prefix = prefix }
            }
            assertFalse(prefix in error.message.orEmpty(), "the message repeats the prefix")
        }
        for (folder in listOf(".", "..", "a/b", "a".repeat(65), "", "tab\tname")) {
            val error = assertFailsWith<IllegalArgumentException>("folder ${folder.length} chars") {
                MediaSaverConfig { folderName = folder }
            }
            if (folder.length > 2) assertFalse(folder in error.message.orEmpty(), "the message repeats the folder")
        }
        MediaSaverConfig {
            prefix = "p".repeat(50)
            folderName = "My_Album-2.v1"
        }
    }

    @Test
    fun copyKeepsUnsetFieldsAndEqualityCoversEveryField() {
        val config = MediaSaverConfig {
            autoSave = true
            prefix = "Shot"
            destination = GalleryDestination.DCIM
            folderName = "Trip"
            imageFormat = ImageFormat.PNG
        }

        assertEquals(config, config.copy { })
        assertEquals(config.hashCode(), config.copy { }.hashCode())
        assertEquals(
            GalleryDestination.DOCUMENTS,
            config.copy {
                destination = GalleryDestination.DOCUMENTS
            }.destination,
        )
        assertEquals("Trip", config.copy { destination = GalleryDestination.DOCUMENTS }.folderName)
        assertNotEquals(config, config.copy { autoSave = false })
        assertNotEquals(config, config.copy { prefix = null })
        assertNotEquals(config, config.copy { folderName = null })
        assertNotEquals(config, config.copy { imageFormat = ImageFormat.JPEG })
    }
}

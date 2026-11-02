package com.kashif.cameraK.enums

import kotlin.test.Test
import kotlin.test.assertEquals

class ImageFormatTest {
    @Test
    fun heicHasExtensionAndMimeType() {
        assertEquals("heic", ImageFormat.HEIC.extension)
        assertEquals("image/heic", ImageFormat.HEIC.mimeType)
    }
}

package com.kashif.cameraK.utils

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `ImageBitmap.toByteArray()` is public camerak API on iOS that nothing in the repository calls, so
 * this test is what keeps it compiling and encoding wherever it lives.
 */
class ImageBitmapEncodingTest {
    @Test
    fun encodesAnImageBitmapToNonEmptyPng() {
        val bytes = assertNotNull(ImageBitmap(4, 3).toByteArray(), "the bitmap encoded to no data")

        assertContentEquals(PNG_SIGNATURE, bytes.copyOf(PNG_SIGNATURE.size), "the encoding is not a PNG")
        assertTrue(bytes.size > PNG_SIGNATURE.size, "the PNG has a signature and nothing after it")
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}

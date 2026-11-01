package com.kashif.imagesaverplugin

import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class JVMImageSaverPluginTest {
    @Test
    fun saveImage_undecodableBytes_returnsNullAndReportsTheFailure() = runTest {
        var saved = false
        var failure: String? = null
        val plugin =
            JVMImageSaverPlugin(ImageSaverConfig(), onImageSaved = { saved = true }, onImageSavedFailed = {
                failure =
                    it
            })

        val path = plugin.saveImage(byteArrayOf(1, 2, 3), imageName = "not-an-image")

        assertNull(path)
        assertFalse(saved)
        assertEquals("Could not decode image bytes", failure)
    }

    @Test
    fun getByteArrayFrom_readsTheFile() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 42)
        val file = File.createTempFile("camerak", ".jpg").apply { deleteOnExit() }
        file.writeBytes(bytes)

        assertContentEquals(bytes, plugin().getByteArrayFrom(file.absolutePath))
    }

    @Test
    fun getByteArrayFrom_missingFile_throwsIOExceptionNamingThePath() {
        val missing = File(System.getProperty("java.io.tmpdir"), "camerak-missing-${System.nanoTime()}.jpg")

        val error = assertFailsWith<IOException> { plugin().getByteArrayFrom(missing.absolutePath) }

        assertEquals("Failed to read image from path: ${missing.absolutePath}", error.message)
    }

    private fun plugin() = JVMImageSaverPlugin(ImageSaverConfig(), onImageSaved = {}, onImageSavedFailed = {})
}

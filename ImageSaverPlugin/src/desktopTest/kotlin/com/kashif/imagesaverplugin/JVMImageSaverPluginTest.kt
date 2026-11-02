package com.kashif.imagesaverplugin

import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class JVMImageSaverPluginTest {
    private val home: File = Files.createTempDirectory("camerak-saver-base").toFile()

    @AfterTest
    fun tearDown() {
        home.deleteRecursively()
    }

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
        val file = File(home, "Pictures/CameraK/camerak.jpg").apply { parentFile.mkdirs() }
        file.writeBytes(bytes)

        assertContentEquals(bytes, plugin().getByteArrayFrom(file.absolutePath))
    }

    @Test
    fun getByteArrayFrom_missingFile_throwsIOExceptionWithoutThePath() {
        val missing = File(home, "Pictures/CameraK/camerak-missing.jpg").apply { parentFile.mkdirs() }

        val error = assertFailsWith<IOException> { plugin().getByteArrayFrom(missing.absolutePath) }

        assertFalse(home.name in error.message.orEmpty(), "the error message contains the path: ${error.message}")
    }

    @Test
    fun readBytes_readsTheFile() = runTest {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 43)
        val file = File(home, "Pictures/CameraK/camerak-read.jpg").apply { parentFile.mkdirs() }
        file.writeBytes(bytes)

        assertContentEquals(bytes, plugin().readBytes(file.absolutePath))
    }

    @Test
    fun readBytes_missingFile_throwsIOExceptionWithoutThePath() = runTest {
        val missing = File(home, "Pictures/CameraK/camerak-missing-${System.nanoTime()}.jpg").apply {
            parentFile.mkdirs()
        }

        val error = assertFailsWith<IOException> { plugin().readBytes(missing.absolutePath) }

        assertFalse(home.name in error.message.orEmpty(), "the error message contains the path: ${error.message}")
    }

    @Test
    fun readBytes_outsideTheOutputFolders_throwsIOExceptionWithoutThePath() = runTest {
        val outside = File(home, "elsewhere/camerak.jpg").apply { parentFile.mkdirs() }
        outside.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 44))

        val error = assertFailsWith<IOException> { plugin().readBytes(outside.absolutePath) }

        assertFalse(home.name in error.message.orEmpty(), "the error message contains the path: ${error.message}")
    }

    private fun plugin() =
        JVMImageSaverPlugin(ImageSaverConfig(), onImageSaved = {}, onImageSavedFailed = {}, userHome = home)
}

package com.kashif.imagesaverplugin

import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * getByteArrayFrom on desktop reads only the library's own output: files under the user's Pictures
 * and Documents folders, where the saver and CameraK's captures write. A path that resolves anywhere
 * else, through `..` or a symbolic link, fails with an [IOException] whose message has no path.
 */
class JVMImageSaverPluginReadTest {

    private val sandbox: File = Files.createTempDirectory("camerak-saver-read").toFile()
    private val home = File(sandbox, "home").apply { mkdirs() }
    private val sut =
        JVMImageSaverPlugin(ImageSaverConfig(), onImageSaved = {}, onImageSavedFailed = {}, userHome = home)

    @AfterTest
    fun tearDown() {
        sandbox.deleteRecursively()
    }

    private fun file(parent: File, name: String, bytes: ByteArray = jpeg()): File {
        parent.mkdirs()
        return File(parent, name).apply { writeBytes(bytes) }
    }

    private fun assertRefused(path: String) {
        val error = assertFailsWith<IOException>("reading $path") { sut.getByteArrayFrom(path) }
        assertFalse(sandbox.name in error.message.orEmpty(), "the error message contains a path: ${error.message}")
    }

    @Test
    fun anImageTheSaverSaved_readsBack() = runTest {
        val bytes = jpeg()
        val saved = assertIs<ImageSaveResult.Saved>(sut.save(bytes, "shot"))

        assertContentEquals(bytes, sut.getByteArrayFrom(saved.location))
    }

    @Test
    fun cameraKCapturesAndDocuments_readBack() {
        val capture = file(File(home, "Pictures/CameraK"), "CameraK.jpg")
        val document = file(File(home, "Documents/Scans"), "scan.jpg")

        assertContentEquals(capture.readBytes(), sut.getByteArrayFrom(capture.path))
        assertContentEquals(document.readBytes(), sut.getByteArrayFrom(document.path))
    }

    @Test
    fun aFileOutsideTheOutputFolders_isRefused() {
        assertRefused(file(File(sandbox, "elsewhere"), "secret.jpg").path)
    }

    @Test
    fun aPathThatClimbsOutOfPictures_isRefused() {
        file(home, "secret.jpg")
        File(home, "Pictures").mkdirs()

        assertRefused(File(home, "Pictures/../secret.jpg").path)
    }

    @Test
    fun aLinkInPicturesToAFileOutside_isRefused() {
        val secret = file(File(sandbox, "elsewhere"), "secret.jpg")
        val pictures = File(home, "Pictures").apply { mkdirs() }
        val link = File(pictures, "linked.jpg")
        Files.createSymbolicLink(link.toPath(), secret.toPath())

        assertRefused(link.path)
    }

    @Test
    fun aMissingFileInPictures_failsWithoutItsPath() {
        File(home, "Pictures").mkdirs()

        assertRefused(File(home, "Pictures/missing.jpg").path)
    }

    private fun jpeg(): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_3BYTE_BGR)
        return ByteArrayOutputStream().also { check(ImageIO.write(image, "jpg", it)) }.toByteArray()
    }
}

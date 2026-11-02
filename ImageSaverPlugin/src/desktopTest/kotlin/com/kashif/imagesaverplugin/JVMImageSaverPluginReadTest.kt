package com.kashif.imagesaverplugin

import com.kashif.cameraK.enums.Directory
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
 * getByteArrayFrom on desktop reads only the library's own output: the folder this saver writes to
 * (`<directory>/<customFolderName or CameraK>`) and the `CameraK` folders under the user's Pictures
 * and Documents, where CameraK's desktop captures go. Any other file, including the user's own files
 * elsewhere in Pictures or Documents, and any path that resolves outside those folders through `..`
 * or a symbolic link, fails with an [IOException] whose message has no path.
 */
class JVMImageSaverPluginReadTest {

    private val sandbox: File = Files.createTempDirectory("camerak-saver-read").toFile()
    private val home = File(sandbox, "home").apply { mkdirs() }
    private val sut = plugin(ImageSaverConfig())

    private fun plugin(config: ImageSaverConfig) =
        JVMImageSaverPlugin(config, onImageSaved = {}, onImageSavedFailed = {}, userHome = home)

    @AfterTest
    fun tearDown() {
        sandbox.deleteRecursively()
    }

    private fun file(parent: File, name: String, bytes: ByteArray = jpeg()): File {
        parent.mkdirs()
        return File(parent, name).apply { writeBytes(bytes) }
    }

    private fun assertRefused(path: String, reader: JVMImageSaverPlugin = sut) {
        val error = assertFailsWith<IOException>("reading $path") { reader.getByteArrayFrom(path) }
        assertFalse(sandbox.name in error.message.orEmpty(), "the error message contains a path: ${error.message}")
    }

    @Test
    fun anImageTheSaverSaved_readsBack() = runTest {
        val bytes = jpeg()
        val saved = assertIs<ImageSaveResult.Saved>(sut.save(bytes, "shot"))

        assertContentEquals(bytes, sut.getByteArrayFrom(saved.location))
    }

    @Test
    fun cameraKCapturesInPicturesAndDocuments_readBack() {
        val picture = file(File(home, "Pictures/CameraK"), "CameraK.jpg")
        val document = file(File(home, "Documents/CameraK"), "CameraK_1.jpg")

        assertContentEquals(picture.readBytes(), sut.getByteArrayFrom(picture.path))
        assertContentEquals(document.readBytes(), sut.getByteArrayFrom(document.path))
    }

    @Test
    fun anImageSavedToACustomFolder_readsBack() = runTest {
        val bytes = jpeg()
        val reader = plugin(ImageSaverConfig(directory = Directory.DOCUMENTS, customFolderName = "Receipts"))
        val saved = assertIs<ImageSaveResult.Saved>(reader.save(bytes, "shot"))

        assertContentEquals(bytes, reader.getByteArrayFrom(saved.location))
    }

    // The user's own files in Pictures and Documents are not the library's output, so the default
    // saver cannot be used to read them.
    @Test
    fun theUsersOtherFilesInPicturesAndDocuments_areRefused() {
        assertRefused(file(File(home, "Documents/taxes"), "return.pdf").path)
        assertRefused(file(File(home, "Documents/Scans"), "scan.jpg").path)
        assertRefused(file(File(home, "Documents"), "notes.jpg").path)
        assertRefused(file(File(home, "Pictures"), "holiday.jpg").path)
        assertRefused(file(File(home, "Pictures/Family"), "birthday.jpg").path)
    }

    @Test
    fun anotherSaversCustomFolder_isRefused() {
        val other = file(File(home, "Pictures/OtherApp"), "shot.jpg")

        assertRefused(other.path, plugin(ImageSaverConfig(customFolderName = "Receipts")))
    }

    @Test
    fun aPathThatClimbsOutOfTheCameraKFolder_isRefused() {
        file(File(home, "Pictures"), "holiday.jpg")
        File(home, "Pictures/CameraK").mkdirs()

        assertRefused(File(home, "Pictures/CameraK/../holiday.jpg").path)
    }

    @Test
    fun aCameraKFolderThatLinksOutOfPictures_isRefused() {
        val secret = file(File(sandbox, "elsewhere"), "secret.jpg")
        val pictures = File(home, "Pictures").apply { mkdirs() }
        Files.createSymbolicLink(File(pictures, "CameraK").toPath(), secret.parentFile.toPath())

        assertRefused(File(pictures, "CameraK/secret.jpg").path)
    }

    // A link that stays inside Pictures still makes the CameraK folder some other folder, so it is not
    // a read root: otherwise `ln -s ~/Pictures ~/Pictures/CameraK` opens the whole of Pictures.
    @Test
    fun aCameraKFolderThatLinksToItsOwnDirectory_isRefused() {
        val pictures = File(home, "Pictures").apply { mkdirs() }
        val holiday = file(pictures, "holiday.jpg")
        Files.createSymbolicLink(File(pictures, "CameraK").toPath(), pictures.toPath())

        assertRefused(holiday.path)
        assertRefused(File(pictures, "CameraK/holiday.jpg").path)
    }

    @Test
    fun aCameraKFolderThatLinksToASiblingFolder_isRefused() {
        val pictures = File(home, "Pictures").apply { mkdirs() }
        val birthday = file(File(pictures, "Family"), "birthday.jpg")
        Files.createSymbolicLink(File(pictures, "CameraK").toPath(), File(pictures, "Family").toPath())

        assertRefused(birthday.path)
        assertRefused(File(pictures, "CameraK/birthday.jpg").path)
    }

    @Test
    fun aCustomFolderThatLinksToASiblingFolder_isRefused() {
        val documents = File(home, "Documents").apply { mkdirs() }
        val taxes = file(File(documents, "taxes"), "return.pdf")
        Files.createSymbolicLink(File(documents, "Receipts").toPath(), File(documents, "taxes").toPath())
        val reader = plugin(ImageSaverConfig(directory = Directory.DOCUMENTS, customFolderName = "Receipts"))

        assertRefused(taxes.path, reader)
        assertRefused(File(documents, "Receipts/return.pdf").path, reader)
    }

    // A folder whose name only starts with CameraK is a different folder; a string-prefix check would
    // accept it.
    @Test
    fun aFolderWhoseNameStartsWithCameraK_isRefused() {
        assertRefused(file(File(home, "Pictures/CameraKevil"), "x.jpg").path)
        assertRefused(file(File(home, "Documents/CameraK-old"), "x.jpg").path)
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
    fun aLinkInTheCameraKFolderToAFileOutside_isRefused() {
        val secret = file(File(sandbox, "elsewhere"), "secret.jpg")
        val cameraK = File(home, "Pictures/CameraK").apply { mkdirs() }
        val link = File(cameraK, "linked.jpg")
        Files.createSymbolicLink(link.toPath(), secret.toPath())

        assertRefused(link.path)
    }

    @Test
    fun aMissingFileInTheCameraKFolder_failsWithoutItsPath() {
        File(home, "Pictures/CameraK").mkdirs()

        assertRefused(File(home, "Pictures/CameraK/missing.jpg").path)
    }

    private fun jpeg(): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_3BYTE_BGR)
        return ByteArrayOutputStream().also { check(ImageIO.write(image, "jpg", it)) }.toByteArray()
    }
}

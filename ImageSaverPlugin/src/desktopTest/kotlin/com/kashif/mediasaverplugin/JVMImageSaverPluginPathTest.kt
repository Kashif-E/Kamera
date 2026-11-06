package com.kashif.imagesaverplugin

import com.kashif.cameraK.capture.path
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Names a consumer passes to the desktop saver (customFolderName, imageName, and the prefix the
 * auto-save names are built from) cannot put a file outside the configured folder: a name with a
 * separator, a `..` segment or a control character is rejected with an [IllegalArgumentException],
 * a folder name that is a symbolic link out of the folder fails with a Storage failure whose cause is
 * an [IOException], and nothing is written anywhere. An auto-save prefix that cannot make a valid name is rejected when
 * the plugin is made.
 */
class JVMImageSaverPluginPathTest {

    // The user's home is a folder inside a sandbox, so a file that escapes it is still caught.
    private val sandbox: File = Files.createTempDirectory("camerak-saver-sandbox").toFile()
    private val home = File(sandbox, "home").apply { mkdirs() }

    @AfterTest
    fun tearDown() {
        sandbox.deleteRecursively()
    }

    private fun plugin(customFolderName: String? = null) =
        desktopSaver(home, MediaSaverConfig { folderName = customFolderName })

    private fun filesUnder(folder: File): List<String> =
        folder.walkTopDown().filter { it.isFile }.map { it.relativeTo(sandbox).path }.toList()

    // The capture's own file is outside the sandbox, so every file under it is one the saver wrote.
    private suspend fun assertRejected(sut: ImageSaverPlugin, imageName: String?) {
        assertFailsWith<IllegalArgumentException>("the error for a rejected name") {
            sut.save(capturedImage(jpeg()), imageName)
        }
        assertEquals(emptyList(), filesUnder(sandbox), "files written for a rejected name")
    }

    private suspend fun ImageSaverPlugin.savedFile(imageName: String?): File {
        val result = save(capturedImage(jpeg()), imageName)
        return File(assertIs<CameraKResult.Success<SavedImage>>(result, "save result: $result").value.asset.path)
    }

    private suspend fun ImageSaverPlugin.assertRefusedAsStorage(imageName: String?, what: String) {
        val result = save(capturedImage(jpeg()), imageName)
        val error =
            assertIs<CameraKError.Storage>(assertIs<CameraKResult.Failure>(result, "save result for $what").error)
        assertIs<IOException>(error.cause, "the cause for $what")
    }

    @Test
    fun anImageNameWithParentSegments_isRejected() = runTest {
        assertRejected(plugin(), "../../escaped")
    }

    @Test
    fun anAbsoluteImageName_isRejected() = runTest {
        assertRejected(plugin(), File(sandbox, "absolute").absolutePath)
    }

    @Test
    fun anImageNameWithASeparator_isRejected() = runTest {
        assertRejected(plugin(), "nested/shot")
    }

    // A folder name is validated when the saver is configured now, as the prefix already was.
    @Test
    fun aCustomFolderWithParentSegments_isRejected() = runTest {
        assertRejectedWhenConfigured("../../escaped")
    }

    @Test
    fun anAbsoluteCustomFolder_isRejected() = runTest {
        assertRejectedWhenConfigured(File(sandbox, "absolute").absolutePath)
    }

    @Test
    fun dotNamesControlCharactersAndOverlongNames_areRejected() = runTest {
        for (name in listOf(".", "..", "shot\u0000", "line\nbreak", "a".repeat(65), "")) {
            assertRejected(plugin(), name)
        }
        assertRejectedWhenConfigured("..")
    }

    private fun assertRejectedWhenConfigured(customFolderName: String) {
        val error = assertFailsWith<IllegalArgumentException> { plugin(customFolderName = customFolderName) }
        if (customFolderName.length > 2) {
            assertFalse(customFolderName in error.message.orEmpty(), "the error message repeats the folder name")
        }
        assertEquals(emptyList(), filesUnder(sandbox), "files written for a rejected folder name")
    }

    // Ported from the 1.x failure callback, which is gone: the rejection's own message carries the reason.
    @Test
    fun aRejectedName_failsWithAReasonThatDoesNotRepeatTheName() = runTest {
        val failure = assertFailsWith<IllegalArgumentException> {
            plugin().save(capturedImage(jpeg()), "../escaped")
        }.message

        assertFalse(failure.isNullOrEmpty(), "the failure has no reason")
        assertFalse("escaped" in failure.orEmpty(), "the failure message repeats the caller's name")
    }

    @Test
    fun plainNames_saveInsideTheConfiguredFolder() = runTest {
        val saved = plugin(customFolderName = "My_Album-2").savedFile("MyApp_1700000000000.v2")

        assertEquals(File(home, "Pictures/My_Album-2").canonicalFile, saved.canonicalFile.parentFile)
        assertEquals("MyApp_1700000000000.v2.jpg", saved.name)
    }

    // Like Android, and like CameraK's own desktop captures, the saver keeps its images in a CameraK
    // folder rather than loose among the user's own files.
    @Test
    fun withNoCustomFolder_imagesSaveInACameraKFolder() = runTest {
        val saved = plugin().savedFile("shot")

        assertEquals(File(home, "Pictures/CameraK").canonicalFile, saved.canonicalFile.parentFile)
    }

    @Test
    fun aCameraKFolderThatLinksOutOfTheFolder_failsAndWritesNothingOutside() = runTest {
        val outside = File(sandbox, "outside").apply { mkdirs() }
        val pictures = File(home, "Pictures").apply { mkdirs() }
        Files.createSymbolicLink(File(pictures, "CameraK").toPath(), outside.toPath())

        plugin().assertRefusedAsStorage("shot", "a folder that links outside")
        assertEquals(emptyList(), outside.list()?.toList(), "files written through the link")
    }

    // The saver writes only where it may read back: a CameraK folder that is a link, even to a
    // folder inside Pictures, is not the saver's folder.
    @Test
    fun aCameraKFolderThatLinksToASiblingFolder_failsAndWritesNothingThere() = runTest {
        val pictures = File(home, "Pictures").apply { mkdirs() }
        val family = File(pictures, "Family").apply { mkdirs() }
        Files.createSymbolicLink(File(pictures, "CameraK").toPath(), family.toPath())

        plugin().assertRefusedAsStorage("shot", "a folder that links to a sibling")
        assertEquals(emptyList(), family.list()?.toList(), "files written through the link")
    }

    @Test
    fun aCustomFolderThatLinksOutOfTheFolder_failsAndWritesNothingOutside() = runTest {
        val outside = File(sandbox, "outside").apply { mkdirs() }
        val pictures = File(home, "Pictures").apply { mkdirs() }
        Files.createSymbolicLink(File(pictures, "linked").toPath(), outside.toPath())

        plugin(customFolderName = "linked").assertRefusedAsStorage("shot", "a folder that links outside")
        assertEquals(emptyList(), outside.list()?.toList(), "files written through the link")
    }

    @Test
    fun anAutoSavePrefixOf50Characters_saves() = runTest {
        val name = autoSaveImageName("p".repeat(50), AUTO_SAVE_MILLIS)

        val saved = plugin().savedFile(name)

        assertEquals("$name.jpg", saved.name)
    }

    @Test
    fun anAutoSavePrefixOf51Characters_isRejected() = runTest {
        assertRejected(plugin(), autoSaveImageName("p".repeat(51), AUTO_SAVE_MILLIS))
    }

    // An auto-save prefix that can never make a valid name is rejected when the plugin is made, not
    // on every capture afterwards.
    @Test
    fun anAutoSavePrefixOver50Characters_isRejectedWhenThePluginIsConfigured() {
        val error = assertFailsWith<IllegalArgumentException> { pluginWithPrefix("p".repeat(51)) }

        assertFalse("p".repeat(51) in error.message.orEmpty(), "the error message repeats the prefix")
    }

    @Test
    fun anAutoSavePrefixWithASeparator_isRejectedWhenThePluginIsConfigured() {
        for (prefix in listOf("../escaped", "nested/shot", "line\nbreak")) {
            assertFailsWith<IllegalArgumentException>("prefix $prefix") { pluginWithPrefix(prefix) }
        }
    }

    @Test
    fun anAutoSavePrefixOf50Characters_isAcceptedWhenThePluginIsConfigured() {
        pluginWithPrefix("p".repeat(50))
        pluginWithPrefix("MyApp.v2-photo_")
    }

    private fun pluginWithPrefix(prefix: String) = desktopSaver(
        home,
        MediaSaverConfig {
            autoSave = true
            this.prefix = prefix
        },
    )

    private fun jpeg(): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_3BYTE_BGR)
        return ByteArrayOutputStream().also { check(ImageIO.write(image, "jpg", it)) }.toByteArray()
    }

    private companion object {
        // A capture time with as many digits as any until the year 2286: 13.
        const val AUTO_SAVE_MILLIS = 1_700_000_000_000L
    }
}

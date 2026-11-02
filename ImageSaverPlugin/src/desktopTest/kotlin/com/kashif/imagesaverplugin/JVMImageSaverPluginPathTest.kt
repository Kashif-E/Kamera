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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Names a consumer passes to the desktop saver (customFolderName, imageName, and the prefix the
 * auto-save names are built from) cannot put a file outside the configured folder: a name with a
 * separator, a `..` segment or a control character is rejected with an [IllegalArgumentException],
 * a customFolderName that is a symbolic link out of the folder fails with an [IOException], and
 * nothing is written anywhere. An auto-save prefix that cannot make a valid name is rejected when
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

    private fun plugin(customFolderName: String? = null, onFailed: (String) -> Unit = {}) = JVMImageSaverPlugin(
        ImageSaverConfig(customFolderName = customFolderName),
        onImageSaved = {},
        onImageSavedFailed = onFailed,
        userHome = home,
    )

    private fun filesUnder(folder: File): List<String> =
        folder.walkTopDown().filter { it.isFile }.map { it.relativeTo(sandbox).path }.toList()

    private suspend fun assertRejected(sut: JVMImageSaverPlugin, imageName: String?) {
        val result = sut.save(jpeg(), imageName)

        val failed = assertIs<ImageSaveResult.Failed>(result, "save result for a name that leaves the folder")
        assertIs<IllegalArgumentException>(failed.error, "the error for a rejected name")
        assertEquals(emptyList(), filesUnder(sandbox), "files written for a rejected name")
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

    @Test
    fun aCustomFolderWithParentSegments_isRejected() = runTest {
        assertRejected(plugin(customFolderName = "../../escaped"), "shot")
    }

    @Test
    fun anAbsoluteCustomFolder_isRejected() = runTest {
        assertRejected(plugin(customFolderName = File(sandbox, "absolute").absolutePath), "shot")
    }

    @Test
    fun dotNamesControlCharactersAndOverlongNames_areRejected() = runTest {
        for (name in listOf(".", "..", "shot\u0000", "line\nbreak", "a".repeat(65), "")) {
            assertRejected(plugin(), name)
        }
        assertRejected(plugin(customFolderName = ".."), "shot")
    }

    @Test
    fun aRejectedName_isReportedThroughTheFailureCallbackAndSaveImageReturnsNull() = runTest {
        var failure: String? = null
        val sut = plugin(onFailed = { failure = it })

        val path = sut.saveImage(jpeg(), "../escaped")

        assertNull(path)
        assertFalse(failure.isNullOrEmpty(), "the failure callback got no reason")
        assertFalse("escaped" in failure.orEmpty(), "the failure message repeats the caller's name")
    }

    @Test
    fun plainNames_saveInsideTheConfiguredFolder() = runTest {
        val result = plugin(customFolderName = "My_Album-2").save(jpeg(), "MyApp_1700000000000.v2")

        val saved = File(assertIs<ImageSaveResult.Saved>(result, "save result: $result").location)
        assertEquals(File(home, "Pictures/My_Album-2").canonicalFile, saved.canonicalFile.parentFile)
        assertEquals("MyApp_1700000000000.v2.jpg", saved.name)
    }

    // Like Android, and like CameraK's own desktop captures, the saver keeps its images in a CameraK
    // folder rather than loose among the user's own files.
    @Test
    fun withNoCustomFolder_imagesSaveInACameraKFolder() = runTest {
        val result = plugin().save(jpeg(), "shot")

        val saved = File(assertIs<ImageSaveResult.Saved>(result, "save result: $result").location)
        assertEquals(File(home, "Pictures/CameraK").canonicalFile, saved.canonicalFile.parentFile)
    }

    @Test
    fun aCameraKFolderThatLinksOutOfTheFolder_failsAndWritesNothingOutside() = runTest {
        val outside = File(sandbox, "outside").apply { mkdirs() }
        val pictures = File(home, "Pictures").apply { mkdirs() }
        Files.createSymbolicLink(File(pictures, "CameraK").toPath(), outside.toPath())

        val result = plugin().save(jpeg(), "shot")

        val failed = assertIs<ImageSaveResult.Failed>(result, "save result for a folder that links outside")
        assertIs<IOException>(failed.error, "the error for a folder that links outside")
        assertEquals(emptyList(), outside.list()?.toList(), "files written through the link")
    }

    // The saver writes only where it may read back: a CameraK folder that is a link, even to a
    // folder inside Pictures, is not the saver's folder.
    @Test
    fun aCameraKFolderThatLinksToASiblingFolder_failsAndWritesNothingThere() = runTest {
        val pictures = File(home, "Pictures").apply { mkdirs() }
        val family = File(pictures, "Family").apply { mkdirs() }
        Files.createSymbolicLink(File(pictures, "CameraK").toPath(), family.toPath())

        val result = plugin().save(jpeg(), "shot")

        val failed = assertIs<ImageSaveResult.Failed>(result, "save result for a folder that links to a sibling")
        assertIs<IOException>(failed.error, "the error for a folder that links to a sibling")
        assertEquals(emptyList(), family.list()?.toList(), "files written through the link")
    }

    @Test
    fun aCustomFolderThatLinksOutOfTheFolder_failsAndWritesNothingOutside() = runTest {
        val outside = File(sandbox, "outside").apply { mkdirs() }
        val pictures = File(home, "Pictures").apply { mkdirs() }
        Files.createSymbolicLink(File(pictures, "linked").toPath(), outside.toPath())

        val result = plugin(customFolderName = "linked").save(jpeg(), "shot")

        val failed = assertIs<ImageSaveResult.Failed>(result, "save result for a folder that links outside")
        assertIs<IOException>(failed.error, "the error for a folder that links outside")
        assertEquals(emptyList(), outside.list()?.toList(), "files written through the link")
    }

    @Test
    fun anAutoSavePrefixOf50Characters_saves() = runTest {
        val name = autoSaveImageName("p".repeat(50), AUTO_SAVE_MILLIS)

        val result = plugin().save(jpeg(), name)

        val saved = File(assertIs<ImageSaveResult.Saved>(result, "save result: $result").location)
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

    private fun pluginWithPrefix(prefix: String) = JVMImageSaverPlugin(
        ImageSaverConfig(isAutoSave = true, prefix = prefix),
        onImageSaved = {},
        onImageSavedFailed = {},
        userHome = home,
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

package com.kashif.imagesaverplugin

import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop saver's callbacks belong to the consumer. A callback that throws cannot turn a saved
 * image into a failure or make save() throw, and the failure callback's message names what went
 * wrong without the file system paths a platform exception carries.
 */
class JVMImageSaverPluginCallbackTest {

    private val home: File = Files.createTempDirectory("camerak-saver-callbacks").toFile()

    @AfterTest
    fun tearDown() {
        home.deleteRecursively()
    }

    @Test
    fun aThrowingOnImageSaved_stillReportsTheSaveAndNoFailure() = runTest {
        var failure: String? = null
        val sut = JVMImageSaverPlugin(
            ImageSaverConfig(),
            onImageSaved = { throw IllegalStateException("a consumer's onImageSaved failed") },
            onImageSavedFailed = { failure = it },
            userHome = home,
        )

        val result = sut.save(jpeg(), "shot")

        val saved = assertIs<ImageSaveResult.Saved>(result, "save result when onImageSaved throws")
        assertTrue(File(saved.location).isFile, "the saved file is missing")
        assertNull(failure, "a saved image was also reported as failed")
    }

    @Test
    fun aThrowingOnImageSavedFailed_stillReturnsTheFailure() = runTest {
        val sut = JVMImageSaverPlugin(
            ImageSaverConfig(),
            onImageSaved = {},
            onImageSavedFailed = { throw IllegalStateException("a consumer's onImageSavedFailed failed") },
            userHome = home,
        )

        val result = sut.save(byteArrayOf(1, 2, 3), "not-an-image")

        assertIs<ImageSaveResult.Failed>(result, "save result when onImageSavedFailed throws")
    }

    @Test
    fun anIoFailure_isReportedWithoutItsPath() = runTest {
        // The configured folder is taken by a file, so creating it fails with an exception whose
        // message is the folder's path.
        File(home, "Pictures").writeText("not a folder")
        var failure: String? = null
        val sut = JVMImageSaverPlugin(
            ImageSaverConfig(),
            onImageSaved = {},
            onImageSavedFailed = { failure = it },
            userHome = home,
        )

        val result = sut.save(jpeg(), "shot")

        assertIs<ImageSaveResult.Failed>(result, "save result when the folder cannot be created")
        val message = assertNotNull(failure, "the failure callback was not called")
        assertFalse(message.isBlank(), "the failure callback got an empty reason")
        assertFalse(home.name in message, "the failure message contains a path: $message")
        assertEquals(listOf("Pictures"), home.list()?.toList(), "files written for a failed save")
    }

    private fun jpeg(): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_3BYTE_BGR)
        return ByteArrayOutputStream().also { check(ImageIO.write(image, "jpg", it)) }.toByteArray()
    }
}

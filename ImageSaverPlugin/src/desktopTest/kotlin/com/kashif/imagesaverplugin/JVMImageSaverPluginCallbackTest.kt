@file:Suppress("DEPRECATION")

package com.kashif.imagesaverplugin

import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.testing.FakeCaptures
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
import kotlin.test.assertTrue

/**
 * The 2.0 saver takes no callbacks: a save's outcome is its return value, so no app code runs
 * inside a save and none can turn a saved image into a failure. A failure names its kind and keeps
 * the platform's exception as its cause, and nothing shown to a user is built from that cause's
 * message, which may hold a path.
 */
class JVMImageSaverPluginCallbackTest {

    private val home: File = Files.createTempDirectory("camerak-saver-callbacks").toFile()

    @AfterTest
    fun tearDown() {
        home.deleteRecursively()
    }

    // Replaces aThrowingOnImageSaved_stillReportsTheSaveAndNoFailure: there is no onImageSaved.
    @Test
    fun aSavedImageIsReportedAsSavedOnly() = runTest {
        val result = legacySaver(ImageSaverConfig(), home).save(jpeg(), "shot")

        val saved = assertIs<ImageSaveResult.Saved>(result, "save result")
        assertTrue(File(saved.location).isFile, "the saved file is missing")
    }

    // Replaces aThrowingOnImageSavedFailed_stillReturnsTheFailure: there is no onImageSavedFailed.
    @Test
    fun aFailedSaveReturnsTheFailure() = runTest {
        val result = legacySaver(ImageSaverConfig(), home).save(byteArrayOf(1, 2, 3), "not-an-image")

        assertIs<ImageSaveResult.Failed>(result, "save result for bytes that are not an image")
    }

    @Test
    fun anIoFailure_isReportedWithoutItsPath() = runTest {
        // The configured folder is taken by a file, so creating it fails with an exception whose
        // message is the folder's path.
        File(home, "Pictures").writeText("not a folder")
        val source = File(home, "capture.jpg").apply { writeBytes(jpeg()) }
        val config = MediaSaverConfig()
        val sut = ImageSaverPlugin(config, DesktopGalleryStore(config, home), { error("never read") })

        val result = sut.save(FakeCaptures.image(path = source.path))

        val error = assertIs<CameraKError.Storage>(assertIs<CameraKResult.Failure>(result).error)
        assertFalse(home.name in error.toString(), "the failure's text contains a path: $error")
        assertEquals(setOf("Pictures", "capture.jpg"), home.list()?.toSet(), "files written for a failed save")
    }

    private fun jpeg(): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_3BYTE_BGR)
        return ByteArrayOutputStream().also { check(ImageIO.write(image, "jpg", it)) }.toByteArray()
    }
}

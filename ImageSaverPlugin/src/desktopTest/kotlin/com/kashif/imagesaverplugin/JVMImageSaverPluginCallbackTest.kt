@file:Suppress("DEPRECATION")

package com.kashif.imagesaverplugin

import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.StorageFailure
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.testing.FakeCaptures
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
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

    // The 1.x case read the failure callback's text; in 2.0 the text a failure reaches anyone through
    // is what the saver logs, while the platform's exception, whose message is the path, stays the cause.
    @Test
    fun anIoFailure_isReportedWithoutItsPath() = runTest {
        // The configured folder is taken by a file, so creating it fails with an exception whose
        // message is the folder's path.
        File(home, "Pictures").writeText("not a folder")
        val source = File(home, "capture.jpg").apply { writeBytes(jpeg()) }
        val config = MediaSaverConfig()
        val sut = ImageSaverPlugin(config, DesktopGalleryStore(config, home), { error("never read") })
        val messages = CopyOnWriteArrayList<String>()
        val causes = CopyOnWriteArrayList<Throwable>()
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, throwable ->
            messages += message
            if (level == CameraKLogger.Level.ERROR && throwable != null) causes += throwable
        }

        val result = sut.save(FakeCaptures.image(path = source.path))

        val error = assertIs<CameraKError.Storage>(assertIs<CameraKResult.Failure>(result).error)
        assertEquals(StorageFailure.NOT_WRITABLE, error.kind)
        val cause = assertNotNull(error.cause, "the platform's exception was not kept as the cause")
        assertTrue(home.path in cause.message.orEmpty(), "the test's premise: the cause names the folder")
        assertTrue(causes.any { it === cause }, "the failure was not logged with its cause: $causes")
        assertTrue(messages.isNotEmpty() && messages.none { it.isBlank() }, "an empty failure text: $messages")
        assertEquals(emptyList(), messages.filter { home.name in it }, "the failure's text contains a path")
        assertEquals(setOf("Pictures", "capture.jpg"), home.list()?.toSet(), "files written for a failed save")
    }

    private fun jpeg(): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_3BYTE_BGR)
        return ByteArrayOutputStream().also { check(ImageIO.write(image, "jpg", it)) }.toByteArray()
    }
}

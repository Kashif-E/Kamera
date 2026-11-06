package com.kashif.mediasaverplugin

import com.kashif.cameraK.capture.path
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * readBytes on desktop reads back an image this saver saved, from its own output folder. It takes
 * only a [SavedImage] the saver made, so no caller's path reaches the store; an image whose file is
 * gone fails with a Storage error whose text has no path.
 */
class JVMImageSaverPluginReadTest {
    private val sandbox: File = Files.createTempDirectory("camerak-saver-read").toFile()
    private val home = File(sandbox, "home").apply { mkdirs() }
    private val sut = desktopSaver(home)

    @AfterTest
    fun tearDown() {
        sandbox.deleteRecursively()
    }

    private suspend fun MediaSaverPlugin.saved(bytes: ByteArray): SavedImage =
        assertIs<CameraKResult.Success<SavedImage>>(save(capturedImage(bytes), "shot"), "save").value

    @Test
    fun anImageTheSaverSaved_readsBack() = runTest {
        val bytes = jpeg()
        val saved = sut.saved(bytes)

        assertContentEquals(bytes, assertIs<CameraKResult.Success<ByteArray>>(sut.readBytes(saved)).value)
    }

    @Test
    fun anImageSavedToACustomFolder_readsBack() = runTest {
        val bytes = jpeg()
        val reader = desktopSaver(
            home,
            MediaSaverConfig {
                destination = GalleryDestination.DOCUMENTS
                folderName = "Receipts"
            },
        )
        val saved = reader.saved(bytes)

        assertContentEquals(bytes, assertIs<CameraKResult.Success<ByteArray>>(reader.readBytes(saved)).value)
    }

    @Test
    fun aMissingFileInTheCameraKFolder_failsWithoutItsPath() = runTest {
        val saved = sut.saved(jpeg())
        File(saved.asset.path).delete()

        val failure = assertIs<CameraKResult.Failure>(sut.readBytes(saved), "reading an image whose file is gone")
        val error = assertIs<CameraKError.Storage>(failure.error)
        assertFalse(sandbox.name in error.cause?.message.orEmpty(), "the error message contains a path: ${error.cause}")
    }

    private fun jpeg(): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_3BYTE_BGR)
        return ByteArrayOutputStream().also { check(ImageIO.write(image, "jpg", it)) }.toByteArray()
    }
}

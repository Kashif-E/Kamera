package com.kashif.imagesaverplugin

import com.kashif.cameraK.enums.ImageFormat
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * The desktop saver persists the captured bytes as they are: a JPEG saved as a JPEG is the same
 * file, metadata included, and a save never overwrites an earlier one.
 */
class JVMImageSaverPluginOutputTest {

    private val home: File = Files.createTempDirectory("camerak-saver-home").toFile()

    @AfterTest
    fun tearDown() {
        home.deleteRecursively()
    }

    private fun plugin(format: ImageFormat = ImageFormat.JPEG) = JVMImageSaverPlugin(
        ImageSaverConfig(imageFormat = format),
        onImageSaved = {},
        onImageSavedFailed = {},
        userHome = home,
    )

    private suspend fun JVMImageSaverPlugin.saved(bytes: ByteArray, name: String?): File {
        val result = save(bytes, name)
        return File(assertIs<ImageSaveResult.Saved>(result, "save result: $result").location)
    }

    @Test
    fun aJpegSavedAsJpeg_isTheCapturedBytesUnchanged() = runTest {
        val captured = jpegWithExif()

        val file = plugin(ImageFormat.JPEG).saved(captured, "shot")

        assertContentEquals(captured, file.readBytes(), "the saved JPEG differs from the captured one")
    }

    @Test
    fun aPngSavedAsPng_isTheCapturedBytesUnchanged() = runTest {
        val captured = encode("png")

        val file = plugin(ImageFormat.PNG).saved(captured, "shot")

        assertContentEquals(captured, file.readBytes(), "the saved PNG differs from the captured one")
    }

    @Test
    fun aJpegSavedAsPng_isConvertedToAPng() = runTest {
        val file = plugin(ImageFormat.PNG).saved(encode("jpg"), "shot")

        assertEquals("png", file.extension)
        assertContentEquals(PNG_MAGIC, file.readBytes().copyOf(PNG_MAGIC.size), "the file is not a PNG")
        assertNotNull(ImageIO.read(file), "the converted PNG is not readable")
    }

    @Test
    fun savingTwiceUnderOneName_keepsBothImages() = runTest {
        val first = encode("jpg", rgb = 0x112233)
        val second = encode("jpg", rgb = 0xCCBBAA)
        val sut = plugin()

        val firstFile = sut.saved(first, "shot")
        val secondFile = sut.saved(second, "shot")

        assertNotEquals(firstFile.canonicalPath, secondFile.canonicalPath, "the second save reused the first's file")
        assertContentEquals(first, firstFile.readBytes(), "the first image was overwritten")
        assertContentEquals(second, secondFile.readBytes(), "the second image was not saved as captured")
    }

    private fun encode(format: String, rgb: Int = 0x336699): ByteArray {
        val image = BufferedImage(16, 8, BufferedImage.TYPE_3BYTE_BGR)
        for (x in 0 until 16) for (y in 0 until 8) image.setRGB(x, y, rgb + x * 0x010101)
        return ByteArrayOutputStream().also { check(ImageIO.write(image, format, it)) }.toByteArray()
    }

    // A camera JPEG carries an APP1 Exif segment (orientation and the like) right after SOI.
    private fun jpegWithExif(): ByteArray {
        val plain = encode("jpg")
        val exifPayload = "Exif\u0000\u0000MM\u0000*\u0000\u0000\u0000\u0008\u0000\u0000".toByteArray(
            Charsets.ISO_8859_1,
        )
        val length = exifPayload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + exifPayload
        return plain.copyOfRange(0, 2) + app1 + plain.copyOfRange(2, plain.size)
    }

    private companion object {
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}

package com.kashif.imagesaverplugin

import com.kashif.cameraK.capture.path
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.result.CameraKResult
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * The desktop saver persists the captured file as it is: a JPEG is saved as the same file,
 * metadata included, as is a PNG, and a save never overwrites an earlier one.
 */
class JVMImageSaverPluginOutputTest {

    private val home: File = Files.createTempDirectory("camerak-saver-home").toFile()

    @AfterTest
    fun tearDown() {
        home.deleteRecursively()
    }

    private fun plugin() = desktopSaver(home)

    private suspend fun ImageSaverPlugin.saved(bytes: ByteArray, name: String?, format: ImageFormat): File {
        val result = save(capturedImage(bytes, format), name)
        return File(assertIs<CameraKResult.Success<SavedImage>>(result, "save result: $result").value.asset.path)
    }

    @Test
    fun aJpegSavedAsJpeg_isTheCapturedBytesUnchanged() = runTest {
        val captured = jpegWithExif()

        val file = plugin().saved(captured, "shot", ImageFormat.JPEG)

        assertContentEquals(captured, file.readBytes(), "the saved JPEG differs from the captured one")
    }

    @Test
    fun aPngSavedAsPng_isTheCapturedBytesUnchanged() = runTest {
        // A text chunk, which a decode and re-encode would drop.
        val captured = pngWithTextChunk()

        val file = plugin().saved(captured, "shot", ImageFormat.PNG)

        assertContentEquals(captured, file.readBytes(), "the saved PNG differs from the captured one")
    }

    @Test
    fun savingTwiceUnderOneName_keepsBothImages() = runTest {
        val first = encode("jpg", rgb = 0x112233)
        val second = encode("jpg", rgb = 0xCCBBAA)
        val sut = plugin()

        val firstFile = sut.saved(first, "shot", ImageFormat.JPEG)
        val secondFile = sut.saved(second, "shot", ImageFormat.JPEG)

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

    // A PNG with a tEXt chunk right after IHDR (8 signature bytes, then a 25-byte IHDR chunk).
    private fun pngWithTextChunk(): ByteArray {
        val plain = encode("png")
        val type = "tEXt".toByteArray(Charsets.ISO_8859_1)
        val data = "Comment\u0000taken by CameraK".toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().apply {
            update(type)
            update(data)
        }
        val chunk = ByteBuffer
            .allocate(12 + data.size)
            .putInt(data.size)
            .put(type)
            .put(data)
            .putInt(crc.value.toInt())
            .array()
        return plain.copyOfRange(0, 33) + chunk + plain.copyOfRange(33, plain.size)
    }
}

package com.kashif.imagesaverplugin

import com.kashif.cameraK.enums.ImageFormat
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The desktop saver persists the captured bytes as they are: a JPEG saved as a JPEG is the same
 * file, metadata included, and a save never overwrites an earlier one. A format change converts
 * once, at JPEG quality 0.95, with transparency flattened onto white.
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
        // A text chunk, which a decode and re-encode would drop.
        val captured = pngWithTextChunk()

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

    @Test
    fun aTransparentPngSavedAsJpeg_isFlattenedOntoWhite() = runTest {
        val transparent = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val png = ByteArrayOutputStream().also { check(ImageIO.write(transparent, "png", it)) }.toByteArray()

        val file = plugin(ImageFormat.JPEG).saved(png, "shot")

        val decoded = assertNotNull(ImageIO.read(file), "the converted JPEG is not readable")
        // A uniform block survives JPEG almost exactly; 250 allows for rounding in the colour
        // conversion, while transparent black drawn without a background would decode near 0.
        for (x in 0 until decoded.width) {
            for (y in 0 until decoded.height) {
                val rgb = decoded.getRGB(x, y)
                val channels = listOf(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF)
                assertTrue(channels.all { it >= 250 }, "pixel ($x, $y) is not white: ${channels.joinToString()}")
            }
        }
    }

    @Test
    fun aPngSavedAsJpeg_isEncodedAtQuality95() = runTest {
        val file = plugin(ImageFormat.JPEG).saved(encode("png"), "shot")

        // The JDK scales the standard luminance table, whose DC entry is 16, by 2 - 2 * quality:
        // 16 * 0.1 rounds to 2 at quality 0.95, while ImageIO's default quality, 0.75, gives 8.
        assertEquals(2, luminanceDcQuantizer(file.readBytes()), "the JPEG's luminance DC quantizer")
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

    // The first entry of the first quantization table (the luminance table's DC quantizer) in the
    // first DQT segment of a baseline JPEG.
    private fun luminanceDcQuantizer(jpeg: ByteArray): Int {
        var i = 2
        while (i + 4 < jpeg.size) {
            check(jpeg[i] == 0xFF.toByte()) { "not a JPEG marker at $i" }
            val marker = jpeg[i + 1].toInt() and 0xFF
            val length = (jpeg[i + 2].toInt() and 0xFF shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (marker == 0xDB) {
                check(jpeg[i + 4].toInt() and 0xF0 == 0) { "the table is not 8-bit" }
                return jpeg[i + 5].toInt() and 0xFF
            }
            i += 2 + length
        }
        error("no DQT segment")
    }

    private companion object {
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}

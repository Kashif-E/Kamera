package com.kashif.qrscannerplugin

import com.kashif.cameraK.frames.FrameRect
import java.awt.image.BufferedImage
import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.google.zxing.BarcodeFormat as ZxingFormat

/**
 * The ZXing decoder Android and desktop share, over the luma the plugin hands it: it reads only the
 * area, only the configured formats, downscales frames larger than 1280 px a side, keeps its slow
 * TRY_HARDER pass periodic, and reports positions in frame pixels.
 */
class ZxingDecoderTest {
    private val logs = LoggedProblems()

    @AfterTest
    fun restoreLogger() = logs.restore()

    private fun decoder(formats: Set<BarcodeFormat> = setOf(BarcodeFormat.QR_CODE)) = ZxingBarcodeDecoder(formats)

    @Test
    fun decodesAQrCode() {
        val found = decoder().decode(codeScene("camerak-desktop-qr", 320, 240).lumaImage())

        assertEquals(listOf("camerak-desktop-qr"), found.map { it.rawValue })
        assertEquals(BarcodeFormat.QR_CODE, found.single().format)
    }

    @Test
    fun decodesALowContrastColouredQrCode() {
        val scene = codeScene("https://example.com/a?b=1", 320, 240, dark = 0x1F3A5F, light = 0xE8D8B0)

        assertEquals(listOf("https://example.com/a?b=1"), decoder().decode(scene.lumaImage()).map { it.rawValue })
    }

    @Test
    fun aFrameWithoutACodeGivesNothingAndLogsNothing() {
        logs.install()
        val random = Random(42)
        val noise = BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 240) for (x in 0 until 240) noise.setRGB(x, y, random.nextInt(0x1000000))
        val decoder = decoder()

        val found =
            List(6) { decoder.decode(noise.lumaImage()) } +
                listOf(decoder.decode(BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB).lumaImage()))

        assertTrue(found.all { it.isEmpty() }, "codes in frames without one: $found")
        assertTrue(logs.entries.isEmpty(), "frames with no code were logged: ${logs.entries}")
    }

    @Test
    fun codesInLargeAndSmallFramesStillDecode() {
        val decoder = decoder()
        val reader = ZxingLumaReader(setOf(BarcodeFormat.QR_CODE))
        for ((width, height) in listOf(640 to 480, 1280 to 720, 1920 to 1080)) {
            val source = decoder.prepare(codeScene("HELLO", width, height).lumaImage())
            val fast = reader.read(source, tryHarder = false, inverted = false)
            val slow = reader.read(source, tryHarder = true, inverted = false)
            assertEquals(listOf("HELLO"), fast.map { it.text }, "fast pass at ${width}x$height")
            assertEquals(listOf("HELLO"), slow.map { it.text }, "slow pass at ${width}x$height")
        }
    }

    @Test
    fun aFrameLargerThan1280IsDownscaled() {
        val source = decoder().prepare(codeScene("HELLO", 1920, 1080).lumaImage())

        assertEquals(960 to 540, source.width to source.height)
    }

    @Test
    fun anAreaIsCutOutBeforeDecoding() {
        val scene = codeScene("HELLO", 64, 48)
        val luma = scene.luma()

        val source = decoder().prepare(scene.lumaImage(area = FrameRect(8, 4, 40, 28)))

        assertEquals(32 to 24, source.width to source.height)
        val expected = ByteArray(32 * 24) { luma[(4 + it / 32) * 64 + 8 + it % 32] }
        assertContentEquals(expected, source.data.copyOf(32 * 24))
    }

    @Test
    fun sameSizeFramesAllocateNoFrameSizedBufferPerAttempt() {
        val decoder = decoder()
        val image = codeScene("HELLO", 1280, 720).lumaImage(area = FrameRect(0, 0, 1280, 700))
        repeat(3) { decoder.prepare(image) }
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().id

        val before = threads.getThreadAllocatedBytes(thread)
        repeat(10) { decoder.prepare(image) }
        val perAttempt = (threads.getThreadAllocatedBytes(thread) - before) / 10

        // A gray copy of the area is 896,000 bytes; the source object itself is tiny.
        assertTrue(perAttempt < 1280 * 720 / 16, "preparing a 1280x700 area allocated $perAttempt bytes")
    }

    @Test
    fun tryHarderRunsOnlyOnAPeriodicSlowPass() {
        val passes = mutableListOf<Pair<Boolean, Boolean>>()
        val decoder = ZxingBarcodeDecoder(setOf(BarcodeFormat.QR_CODE)) { _, tryHarder, inverted ->
            passes += tryHarder to inverted
            emptyList()
        }
        val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB).lumaImage()

        repeat(10) { decoder.decode(image) }

        val normal = passes.filterNot { it.second }
        val slow = normal.count { it.first }
        assertEquals(10, normal.size, "decode attempts")
        assertTrue(slow in 1..2, "TRY_HARDER ran on $slow of 10 empty-scene attempts")
    }

    @Test
    fun aLightOnDarkCodeIsReadOnThePeriodicInvertedPass() {
        val scene = codeScene("INVERTED", 240, 240, dark = 0xFFFFFF, light = 0x000000)
        val decoder = decoder()

        val found = List(3) { decoder.decode(scene.lumaImage()) }

        assertEquals(listOf("INVERTED"), found.flatten().map { it.rawValue }, "codes over three attempts")
    }

    @Test
    fun onlyTheConfiguredFormatsAreRead() {
        val scene = codeScene("CAMERAK-128", 480, 240, format = ZxingFormat.CODE_128)

        assertEquals(emptyList(), decoder(setOf(BarcodeFormat.QR_CODE)).decode(scene.lumaImage()))
        val found = decoder(setOf(BarcodeFormat.QR_CODE, BarcodeFormat.CODE_128)).decode(scene.lumaImage()).single()
        assertEquals("CAMERAK-128", found.rawValue)
        assertEquals(BarcodeFormat.CODE_128, found.format)
    }

    @Test
    fun aCodeOutsideTheAreaIsNotRead() {
        val scene = codeScene("LEFT", 480, 240, left = 20, top = 30)

        assertEquals(emptyList(), decoder().decode(scene.lumaImage(area = FrameRect(240, 0, 480, 240))))
        assertEquals(
            listOf("LEFT"),
            decoder().decode(scene.lumaImage(area = FrameRect(0, 0, 240, 240))).map {
                it.rawValue
            },
        )
    }

    @Test
    fun theBoundingBoxIsInFramePixels() {
        // A 180 px code (three quarters of the scene's 240 px height) at (300, 30), decoded from the
        // area right of x = 200.
        val scene = codeScene("WHERE", 600, 240, left = 300, top = 30)

        val box =
            assertNotNull(decoder().decode(scene.lumaImage(area = FrameRect(200, 0, 600, 240))).single().boundingBox)

        // ZXing reports the finder patterns' centres, 3.5 modules inside the code's corners, and a
        // 21-module code at 180 px has modules about 8 px wide: the box lies within the code's square,
        // at most 4.5 modules (40 px) in from each edge.
        val code = listOf(300, 30, 480, 210)
        val edges = box.edges()
        for (i in 0..3) {
            val inward = if (i < 2) edges[i] - code[i] else code[i] - edges[i]
            assertTrue(inward in 0..40, "edge $i of $edges against the code at $code")
        }
    }

    @Test
    fun aPortrait1DBarcodeDecodesByTheFramesRotation() {
        // #168: a barcode printed across the scene, in a portrait frame that needs 90 degrees clockwise.
        val frame = codeScene("PORTRAIT-128", 480, 240, format = ZxingFormat.CODE_128).turnedAnticlockwise()

        val found = decoder(setOf(BarcodeFormat.CODE_128)).decode(frame.lumaImage(rotationDegrees = 90))

        assertEquals(listOf("PORTRAIT-128"), found.map { it.rawValue })
        val box = assertNotNull(found.single().boundingBox).edges()
        assertTrue(box[3] - box[1] > box[2] - box[0], "the code runs down the portrait frame: $box")
        assertTrue(box[0] >= 0 && box[1] >= 0 && box[2] <= 240 && box[3] <= 480, "the box is inside the frame: $box")
    }

    @Test
    fun aQrCodesBytesAreItsByteSegments() {
        val found = decoder().decode(codeScene("héllo", 240, 240).lumaImage()).single()

        assertEquals("héllo", found.rawValue)
        assertContentEquals("héllo".encodeToByteArray(), found.rawBytes)
    }

    // #188: 1.x's Android scanner never tried Data Matrix, which its fixed format list left out. In 2.0 an
    // app lists the formats it reads, and the decoder Android and desktop share reads a Data Matrix.
    @Test
    fun decodesADataMatrixWhenItIsAConfiguredFormat() {
        val scene = codeScene("CAMERAK-DM-188", 320, 240, format = ZxingFormat.DATA_MATRIX)

        val found = decoder(setOf(BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX)).decode(scene.lumaImage())

        assertEquals(listOf("CAMERAK-DM-188"), found.map { it.rawValue })
        assertEquals(BarcodeFormat.DATA_MATRIX, found.single().format)
    }

    @Test
    fun aDataMatrixIsNotReadUnderTheQrOnlyDefault() {
        val scene = codeScene("CAMERAK-DM-188", 320, 240, format = ZxingFormat.DATA_MATRIX)

        assertEquals(emptyList(), decoder().decode(scene.lumaImage()))
    }
}

package com.kashif.ocrPlugin

import com.kashif.cameraK.result.CameraKResult
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Desktop OCR frees every native allocation it makes exactly once: the text buffer and image of each
 * scan, and the TessBaseAPI of each processor, including the one-shot extractor's. It hands the frame
 * to Tesseract in a fixed number of native calls, not one per pixel.
 */
class OcrProcessorNativeLeaksTest {

    @Test
    fun everyScan_freesTheTextBufferTesseractReturned_exactlyOnce() {
        val tesseract = FakeTesseract()
        val processor = OCRProcessor(tesseract, TESSDATA)

        repeat(5) { assertEquals("HELLO", processor.scanImage(colourFrame(16, 8))) }
        processor.close()

        assertEquals(5, tesseract.texts.size, "text buffers returned by GetUTF8Text")
        assertEquals(List(5) { 1 }, tesseract.texts.map { it.deletes.get() }, "TessDeleteText count per buffer")
        assertEquals(emptyList(), tesseract.violations.toList())
    }

    @Test
    fun everyScan_freesEveryImageItAllocated_exactlyOnce() {
        val tesseract = FakeTesseract()
        val processor = OCRProcessor(tesseract, TESSDATA)

        repeat(5) { processor.scanImage(colourFrame(16, 8)) }
        processor.close()

        assertEquals(
            List(tesseract.pixes.size) { 1 },
            tesseract.pixes.map { it.destroyed.get() },
            "pixDestroy count per PIX",
        )
        assertEquals(emptyList(), tesseract.violations.toList())
    }

    @Test
    fun close_freesTheTessBaseApi_exactlyOnce() {
        val tesseract = FakeTesseract()
        val processor = OCRProcessor(tesseract, TESSDATA)
        processor.scanImage(colourFrame(16, 8))

        processor.close()
        processor.close()

        val engine = tesseract.engines.single()
        assertEquals(1, engine.ends.get(), "End() count")
        assertEquals(1, engine.deletes.get(), "TessBaseAPI free count")
        assertEquals(emptyList(), tesseract.violations.toList())
    }

    @Test
    fun oneShotExtraction_freesTheTessBaseApiOfEveryProcessorItCreates() {
        val tesseract = FakeTesseract()

        val plugin = OcrPlugin(OcrConfig(), DesktopTextRecognizers(tesseract) { TESSDATA })
        val image = File.createTempFile("ocr-still", ".png")
        try {
            ImageIO.write(colourFrame(16, 8), "png", image)
            repeat(3) {
                val result = runBlocking { plugin.recognize(capture(image.absolutePath)) }
                assertEquals("HELLO", assertIs<CameraKResult.Success<RecognizedText>>(result).value.text)
            }
        } finally {
            image.delete()
        }

        assertTrue(tesseract.engines.isNotEmpty(), "no TessBaseAPI was created")
        assertEquals(
            List(tesseract.engines.size) {
                1
            },
            tesseract.engines.map { it.ends.get() },
            "End() count per engine",
        )
        assertEquals(
            List(tesseract.engines.size) { 1 },
            tesseract.engines.map { it.deletes.get() },
            "TessBaseAPI free count per engine",
        )
        assertEquals(List(tesseract.texts.size) { 1 }, tesseract.texts.map { it.deletes.get() }, "TessDeleteText count")
        assertEquals(emptyList(), tesseract.violations.toList())
    }

    @Test
    fun aScan_crossesIntoNativeCodeAFixedNumberOfTimes_notOncePerPixel() {
        // 16 x 8 and 64 x 32 frames: 128 and 2,048 pixels. At 720p a per-pixel fill is 921,600 JNI calls.
        val small = nativeCallsForOneScan(colourFrame(16, 8))
        val large = nativeCallsForOneScan(colourFrame(64, 32))

        assertEquals(small, large, "native calls for a 64x32 scan vs a 16x8 scan")
    }

    @Test
    fun theImageTesseractReceives_isTheFrameInGray_rowForRow() {
        val frame = grayFrame(13, 7)
        val tesseract = FakeTesseract()

        OCRProcessor(tesseract, TESSDATA).scanImage(frame)

        assertEquals(FakeTesseract.GrayImage(13, 7, frame.lumaValues()), tesseract.lastImage)
    }

    @Test
    fun theImageTesseractReceives_fromASubimage_isThatRegionOnly() {
        // A subimage shares its parent's buffer, so its rows are the parent's stride apart and start at an offset.
        val parent = grayFrame(20, 12)
        val region = parent.getSubimage(3, 2, 11, 6)
        val tesseract = FakeTesseract()

        OCRProcessor(tesseract, TESSDATA).scanImage(region)

        assertEquals(FakeTesseract.GrayImage(11, 6, region.lumaValues()), tesseract.lastImage)
    }

    @Test
    fun theRealTesseract_readsRenderedText() {
        val tessdata = File(checkNotNull(System.getProperty("ocr.tessdataDir")) { "ocr.tessdataDir is not set" })
        assertTrue(
            File(tessdata, "eng.traineddata").exists(),
            "eng.traineddata not found under ${tessdata.absolutePath}",
        )
        val processor = OCRProcessor(JavaCppTesseract, tessdata.absolutePath)
        try {
            val text = processor.scanImage(renderedText("HELLO 42"))
            assertNotNull(text, "Tesseract read nothing")
            assertTrue("HELLO" in text && "42" in text, "Tesseract read \"$text\"")
        } finally {
            processor.close()
        }
    }

    private fun nativeCallsForOneScan(frame: BufferedImage): Int {
        val tesseract = FakeTesseract()
        val processor = OCRProcessor(tesseract, TESSDATA)
        val before = tesseract.crossings.get()
        processor.scanImage(frame)
        return tesseract.crossings.get() - before
    }

    private fun colourFrame(width: Int, height: Int) = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)

    /** A gray frame whose every pixel differs from its neighbours, so a misplaced row or column shows. */
    private fun grayFrame(width: Int, height: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        for (y in 0 until height) {
            for (x in 0 until width) image.raster.setSample(x, y, 0, (x * 7 + y * 31 + 5) and 0xFF)
        }
        return image
    }

    private fun BufferedImage.lumaValues(): List<Int> = List(width * height) { i ->
        raster.getSample(
            i % width,
            i / width,
            0,
        )
    }

    private fun renderedText(text: String): BufferedImage {
        val image = BufferedImage(480, 120, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, image.width, image.height)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color.BLACK
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 64)
            g.drawString(text, 24, 84)
        } finally {
            g.dispose()
        }
        return image
    }

    private companion object {
        const val TESSDATA = "tessdata"
    }
}

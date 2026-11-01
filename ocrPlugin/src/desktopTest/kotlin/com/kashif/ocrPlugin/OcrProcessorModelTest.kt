package com.kashif.ocrPlugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A processor with no usable language data fails when it is created, so the plugin can report it,
 * instead of logging once and returning null from every scan. It frees its TessBaseAPI either way.
 */
class OcrProcessorModelTest {

    @Test
    fun noTessdataDirectory_failsAtCreationAndFreesTheEngine() {
        val tesseract = FakeTesseract()

        assertFailsWith<IllegalStateException> { OCRProcessor(tesseract, tessdataDir = null, clock = { 0L }) }

        assertEquals(1, tesseract.engines.single().deletes.get(), "TessBaseAPI frees")
        assertEquals(emptyList(), tesseract.violations.toList())
    }

    @Test
    fun tesseractRejectingTheLanguageData_failsAtCreationAndFreesTheEngine() {
        val tesseract = FakeTesseract(initResult = -1)

        assertFailsWith<IllegalStateException> { OCRProcessor(tesseract, tessdataDir = "tessdata", clock = { 0L }) }

        assertEquals(1, tesseract.engines.single().deletes.get(), "TessBaseAPI frees")
        assertEquals(emptyList(), tesseract.violations.toList())
    }
}

package com.kashif.ocrPlugin

import org.bytedeco.javacpp.BytePointer
import org.bytedeco.leptonica.PIX
import org.bytedeco.leptonica.global.leptonica.pixCreate
import org.bytedeco.leptonica.global.leptonica.pixDestroy
import org.bytedeco.leptonica.global.leptonica.pixSetPixel
import org.bytedeco.tesseract.TessBaseAPI
import org.bytedeco.tesseract.global.tesseract.TessDeleteText

/**
 * The Tesseract and Leptonica calls [OCRProcessor] makes. Each method is one crossing into native
 * code, and every handle it returns is native memory the caller must free, so a test can stand in
 * for the library and check that each allocation is freed exactly once and never used afterwards.
 */
internal interface TesseractLibrary {
    /** Allocates a TessBaseAPI. */
    fun newEngine(): Engine

    /** Allocates a Leptonica PIX of [width] x [height] at [depth] bits per pixel. */
    fun newPix(width: Int, height: Int, depth: Int): Pix

    interface Engine {
        fun init(dataPath: String, language: String): Int

        fun setVariable(name: String, value: String)

        fun setPageSegMode(mode: Int)

        /** Hands Tesseract [height] rows of [bytesPerLine] bytes each from [pixels]; Tesseract copies them. */
        fun setImage(pixels: ByteArray, width: Int, height: Int, bytesPerPixel: Int, bytesPerLine: Int)

        fun setImage(pix: Pix)

        /** GetUTF8Text: null when Tesseract returned no buffer. A returned buffer must be freed with [Text.delete]. */
        fun recognizedText(): Text?

        /** Frees the recognition results and the image held since the last [setImage]. */
        fun clear()

        /** Frees the language data and everything Init allocated. */
        fun end()

        /** Frees the TessBaseAPI itself. */
        fun delete()
    }

    interface Text {
        fun read(): String

        /** TessDeleteText. */
        fun delete()
    }

    interface Pix {
        fun setPixel(x: Int, y: Int, value: Int)

        /** pixDestroy. */
        fun destroy()
    }
}

/** [TesseractLibrary] over the JavaCPP presets. */
internal object JavaCppTesseract : TesseractLibrary {
    override fun newEngine(): TesseractLibrary.Engine = Engine(TessBaseAPI())

    override fun newPix(width: Int, height: Int, depth: Int): TesseractLibrary.Pix =
        Pix(pixCreate(width, height, depth))

    private class Engine(private val api: TessBaseAPI) : TesseractLibrary.Engine {
        override fun init(dataPath: String, language: String): Int = api.Init(dataPath, language)

        override fun setVariable(name: String, value: String) {
            api.SetVariable(name, value)
        }

        override fun setPageSegMode(mode: Int) = api.SetPageSegMode(mode)

        override fun setImage(pixels: ByteArray, width: Int, height: Int, bytesPerPixel: Int, bytesPerLine: Int) =
            api.SetImage(pixels, width, height, bytesPerPixel, bytesPerLine)

        override fun setImage(pix: TesseractLibrary.Pix) = api.SetImage((pix as Pix).pix)

        override fun recognizedText(): TesseractLibrary.Text? = api.GetUTF8Text()?.let(::Text)

        override fun clear() = api.Clear()

        override fun end() = api.End()

        override fun delete() = api.close()
    }

    // GetUTF8Text's buffer has no JavaCPP deallocator, so BytePointer.deallocate() would not free it.
    private class Text(private val text: BytePointer) : TesseractLibrary.Text {
        override fun read(): String = text.string

        override fun delete() = TessDeleteText(text)
    }

    private class Pix(val pix: PIX) : TesseractLibrary.Pix {
        override fun setPixel(x: Int, y: Int, value: Int) {
            pixSetPixel(pix, x, y, value)
        }

        override fun destroy() = pixDestroy(pix)
    }
}

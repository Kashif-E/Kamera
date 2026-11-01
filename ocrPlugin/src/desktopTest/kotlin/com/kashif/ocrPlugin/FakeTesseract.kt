package com.kashif.ocrPlugin

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stands in for Tesseract and Leptonica with the same ownership rules: every handle it returns must
 * be freed exactly once and never touched afterwards, and End() or delete() while a recognition is
 * inside the engine frees memory that recognition is using. It records each breach in [violations]
 * instead of crashing the JVM, and counts every crossing into "native" code in [crossings].
 */
internal class FakeTesseract(private val recognizedText: String? = "HELLO") : TesseractLibrary {
    val violations: MutableList<String> = CopyOnWriteArrayList()
    val crossings = AtomicInteger()
    val engines: MutableList<FakeEngine> = CopyOnWriteArrayList()
    val texts: MutableList<FakeText> = CopyOnWriteArrayList()
    val pixes: MutableList<FakePix> = CopyOnWriteArrayList()

    /** The last image handed to an engine, as one luma value per pixel, row-major. */
    @Volatile var lastImage: GrayImage? = null

    /** When set, recognitions wait on it, so a test can act while a recognition is in flight. */
    @Volatile var recognitionGate: CountDownLatch? = null
    val recognitionEntered = CountDownLatch(1)

    data class GrayImage(val width: Int, val height: Int, val pixels: List<Int>)

    override fun newEngine(): TesseractLibrary.Engine {
        crossings.incrementAndGet()
        return FakeEngine().also(engines::add)
    }

    override fun newPix(width: Int, height: Int, depth: Int): TesseractLibrary.Pix {
        crossings.incrementAndGet()
        return FakePix(width, height).also(pixes::add)
    }

    inner class FakeEngine : TesseractLibrary.Engine {
        val ends = AtomicInteger()
        val deletes = AtomicInteger()
        private val inFlight = AtomicInteger()

        private fun use(operation: String) {
            crossings.incrementAndGet()
            if (deletes.get() > 0) violations += "$operation after the TessBaseAPI was freed"
            if (ends.get() > 0) violations += "$operation after End()"
        }

        override fun init(dataPath: String, language: String): Int {
            use("Init")
            return 0
        }

        override fun setVariable(name: String, value: String) = use("SetVariable")

        override fun setPageSegMode(mode: Int) = use("SetPageSegMode")

        override fun setImage(pixels: ByteArray, width: Int, height: Int, bytesPerPixel: Int, bytesPerLine: Int) {
            use("SetImage")
            check(bytesPerPixel == 1) { "the fake reads 8-bit gray only" }
            lastImage = GrayImage(
                width,
                height,
                List(width * height) { i -> pixels[(i / width) * bytesPerLine + i % width].toInt() and 0xFF },
            )
        }

        override fun setImage(pix: TesseractLibrary.Pix) {
            use("SetImage")
            pix as FakePix
            if (pix.destroyed.get() > 0) violations += "SetImage with a destroyed PIX"
            lastImage = GrayImage(pix.width, pix.height, pix.pixels.toList())
        }

        override fun recognizedText(): TesseractLibrary.Text? {
            use("GetUTF8Text")
            inFlight.incrementAndGet()
            try {
                recognitionEntered.countDown()
                recognitionGate?.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                return recognizedText?.let { FakeText(it).also(texts::add) }
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override fun clear() = use("Clear")

        override fun end() {
            crossings.incrementAndGet()
            if (inFlight.get() > 0) violations += "End() while a recognition was in flight"
            if (deletes.get() > 0) violations += "End() after the TessBaseAPI was freed"
            ends.incrementAndGet()
        }

        override fun delete() {
            crossings.incrementAndGet()
            if (inFlight.get() > 0) violations += "TessBaseAPI freed while a recognition was in flight"
            if (deletes.incrementAndGet() > 1) violations += "TessBaseAPI freed twice"
        }
    }

    inner class FakeText(private val value: String) : TesseractLibrary.Text {
        val deletes = AtomicInteger()

        override fun read(): String {
            crossings.incrementAndGet()
            if (deletes.get() > 0) violations += "text read after TessDeleteText"
            return value
        }

        override fun delete() {
            crossings.incrementAndGet()
            if (deletes.incrementAndGet() > 1) violations += "text buffer freed twice"
        }
    }

    inner class FakePix(val width: Int, val height: Int) : TesseractLibrary.Pix {
        val pixels = IntArray(width * height)
        val destroyed = AtomicInteger()

        override fun setPixel(x: Int, y: Int, value: Int) {
            crossings.incrementAndGet()
            if (destroyed.get() > 0) violations += "pixSetPixel on a destroyed PIX"
            pixels[y * width + x] = value
        }

        override fun destroy() {
            crossings.incrementAndGet()
            if (destroyed.incrementAndGet() > 1) violations += "PIX destroyed twice"
        }
    }

    private companion object {
        // Upper bound so a broken test cannot hang the suite; tests open the gate long before this.
        const val GATE_TIMEOUT_SECONDS = 10L
    }
}

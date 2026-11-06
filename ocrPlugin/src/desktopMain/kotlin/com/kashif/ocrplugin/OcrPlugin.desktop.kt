package com.kashif.ocrplugin

import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.ReentrantLock
import org.bytedeco.tesseract.global.tesseract.OEM_DEFAULT
import org.bytedeco.tesseract.global.tesseract.OEM_LSTM_ONLY
import org.bytedeco.tesseract.global.tesseract.OEM_TESSERACT_ONLY
import org.bytedeco.tesseract.global.tesseract.PSM_AUTO
import java.awt.image.BufferedImage
import java.awt.image.ComponentSampleModel
import java.awt.image.DataBufferByte
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO

/**
 * Desktop OCR on the Tesseract engine, safe to call from any thread.
 *
 * Creating a processor fails when Tesseract's language data is missing or unusable, so the caller
 * can report it; a processor that exists can always scan until it is closed. At [OcrLevel.ACCURATE]
 * it runs Tesseract's LSTM engine; at [OcrLevel.FAST] its legacy engine when the language data has
 * one, and its default engine otherwise, which it logs once.
 */
public class OCRProcessor internal constructor(
    private val library: TesseractLibrary,
    tessdataDir: String?,
    level: OcrLevel = OcrLevel.ACCURATE,
    private val noLegacyEngineLogged: LoggedOnce = NoLegacyEngineLogged,
) {
    /**
     * Loads `eng.traineddata` from the first source [config] and the process offer (see [OcrConfig])
     * for [config]'s level.
     *
     * @throws TessdataNotFoundException when no source has the language data.
     * @throws IllegalStateException when Tesseract cannot load it.
     */
    public constructor(config: OcrConfig = OcrConfig()) : this(
        JavaCppTesseract,
        findTessdataDirectory(processTessdataSources(config)),
        config.level,
    )

    private val api = library.newEngine()
    private val lock = ReentrantLock()

    // Guarded by lock: set by close(), after which nothing calls into Tesseract.
    private var isClosed = false

    init {
        var initialized = false
        try {
            if (tessdataDir == null) {
                throw TessdataNotFoundException(
                    "No Tesseract language data: set OcrConfig.tessdataPath, package eng.traineddata, or set TESSDATA_PREFIX",
                )
            }
            initialize(tessdataDir, level)
            initialized = true
            api.setVariable(
                "tessedit_char_whitelist",
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.,!?",
            )
            api.setPageSegMode(PSM_AUTO)
        } catch (e: Throwable) {
            try {
                if (initialized) api.end()
            } finally {
                api.delete()
            }
            throw e
        }
    }

    private fun initialize(tessdataDir: String, level: OcrLevel) {
        if (level == OcrLevel.ACCURATE) {
            check(api.init(tessdataDir, LANGUAGE, OEM_LSTM_ONLY) == 0) { "Tesseract could not load eng.traineddata" }
            return
        }
        if (api.init(tessdataDir, LANGUAGE, OEM_TESSERACT_ONLY) == 0) return
        if (noLegacyEngineLogged.claim()) {
            CameraKLogger.w(
                TAG,
                "OcrPlugin: the language data has no legacy Tesseract engine, so FAST runs the default engine, as ACCURATE does",
            )
        }
        check(api.init(tessdataDir, LANGUAGE, OEM_DEFAULT) == 0) { "Tesseract could not load eng.traineddata" }
    }

    /**
     * Scans [image] for text. Returns null when there is none, another scan is in progress, the
     * processor is closed, or Tesseract fails; a failure is logged.
     */
    public fun scanImage(image: BufferedImage): String? = try {
        scan(image)
    } catch (e: Exception) {
        CameraKLogger.e(TAG, "OcrPlugin: Text recognition failed for a frame", e)
        null
    }

    /**
     * Scans [image] in gray. Null when there is no text, another scan is in progress, or the
     * processor is closed; throws when Tesseract fails.
     */
    internal fun scan(image: BufferedImage): String? {
        val grayImage = grayOf(image)
        val raster = grayImage.raster
        val pixels = (raster.dataBuffer as DataBufferByte).data
        val bytesPerLine = (raster.sampleModel as ComponentSampleModel).scanlineStride
        return scanGray(pixels, grayImage.width, grayImage.height, bytesPerLine)
    }

    /** Scans [width] x [height] gray bytes with no row padding, as [scan] does. */
    internal fun scanLuma(pixels: ByteArray, width: Int, height: Int): String? = scanGray(pixels, width, height, width)

    private fun scanGray(pixels: ByteArray, width: Int, height: Int, bytesPerLine: Int): String? {
        if (!lock.tryLock()) return null
        return try {
            if (isClosed) return null
            // One native call for the whole frame; Tesseract copies the rows into its own image.
            api.setImage(pixels, width, height, 1, bytesPerLine)
            val result =
                try {
                    recognizeText()
                } finally {
                    api.clear()
                }
            result.takeIf { !it.isNullOrBlank() }
        } finally {
            lock.unlock()
        }
    }

    private fun grayOf(image: BufferedImage): BufferedImage {
        val grayImage = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_GRAY)
        val g = grayImage.graphics
        g.drawImage(image, 0, 0, null)
        g.dispose()
        return grayImage
    }

    /** Reads Tesseract's text for the current image and frees the buffer it came in. */
    private fun recognizeText(): String? {
        val text = api.recognizedText() ?: return null
        return try {
            text.read().trim()
        } finally {
            text.delete()
        }
    }

    /**
     * Releases Tesseract resources. Waits for a scan in progress on another thread to return first;
     * later calls, and any scan after this one, do nothing.
     */
    public fun close() {
        lock.lock()
        try {
            if (isClosed) return
            isClosed = true
            try {
                api.end()
            } finally {
                api.delete()
            }
        } finally {
            lock.unlock()
        }
    }

    private companion object {
        const val LANGUAGE = "eng"
        val NoLegacyEngineLogged = LoggedOnce()
    }
}

/**
 * Thrown when desktop OCR finds no `eng.traineddata` in any source [OcrConfig] lists.
 */
public class TessdataNotFoundException(message: String) : IllegalStateException(message)

internal actual fun platformTextRecognizers(config: OcrConfig): TextRecognizerFactory = DesktopTextRecognizers(
    JavaCppTesseract,
) { findTessdataDirectory(processTessdataSources(config)) }

/**
 * The desktop recognizers: Tesseract, reading luma for live frames and a still decoded with ImageIO.
 * Tesseract reports no positions, so each recognition is one block without bounds. [tessdataDir]
 * is looked up for each recognizer.
 */
internal class DesktopTextRecognizers(private val library: TesseractLibrary, private val tessdataDir: () -> String?) :
    TextRecognizerFactory {
    override fun live(level: OcrLevel): LiveTextRecognizer =
        TesseractLiveRecognizer(OCRProcessor(library, tessdataDir(), level))

    override fun still(level: OcrLevel): StillTextRecognizer = TesseractStillRecognizer {
        OCRProcessor(library, tessdataDir(), level)
    }
}

private class TesseractLiveRecognizer(private val processor: OCRProcessor) : LiveTextRecognizer {
    private var upright = ByteArray(0)

    override fun recognize(image: LumaImage): List<FoundBlock> {
        val text =
            if (image.rotationDegrees % FULL_TURN == 0) {
                processor.scanLuma(image.bytes, image.width, image.height)
            } else {
                if (upright.size < image.width * image.height) upright = ByteArray(image.width * image.height)
                val turned = image.rotateInto(upright)
                processor.scanLuma(upright, turned.first, turned.second)
            }
        return listOfNotNull(text?.let { FoundBlock(it, null, null) })
    }

    override fun close() = processor.close()
}

private class TesseractStillRecognizer(private val newProcessor: () -> OCRProcessor) : StillTextRecognizer {
    private var processor: OCRProcessor? = null

    override suspend fun recognize(path: String): List<FoundBlock> {
        val image = ImageIO.read(File(path)) ?: throw IOException("The image could not be decoded")
        val created = newProcessor().also { processor = it }
        return listOfNotNull(created.scan(image)?.let { FoundBlock(it, null, null) })
    }

    override fun close() {
        processor?.close()
        processor = null
    }
}

/** Turns this image upright into [destination]; returns the upright width and height. */
internal fun LumaImage.rotateInto(destination: ByteArray): Pair<Int, Int> {
    val w = width
    val h = height
    when (rotationDegrees) {
        90 -> for (y in 0 until h) for (x in 0 until w) destination[x * h + (h - 1 - y)] = bytes[y * w + x]
        180 -> for (y in 0 until h) for (x in 0 until w) destination[(h - 1 - y) * w + (w - 1 - x)] = bytes[y * w + x]
        270 -> for (y in 0 until h) for (x in 0 until w) destination[(w - 1 - x) * h + y] = bytes[y * w + x]
        else -> bytes.copyInto(destination, 0, 0, w * h)
    }
    return if (rotationDegrees == 90 || rotationDegrees == 270) h to w else w to h
}

private const val TAG = "CameraK"
private const val FULL_TURN = 360

/** A message logged at most once: [claim] is true for the first caller only. */
internal class LoggedOnce {
    private val logged = atomic(false)

    fun claim(): Boolean = logged.compareAndSet(false, true)
}

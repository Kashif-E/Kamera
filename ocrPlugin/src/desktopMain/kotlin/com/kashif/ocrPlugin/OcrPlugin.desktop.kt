package com.kashif.ocrPlugin
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bytedeco.tesseract.global.tesseract.PSM_AUTO
import java.awt.image.BufferedImage
import java.awt.image.ComponentSampleModel
import java.awt.image.DataBufferByte

/**
 * Desktop implementation of OCR text recognition using Tesseract engine.
 *
 * Processes BufferedImage frames to extract text using Tesseract OCR with thread-safe locking. It
 * reads every frame it is given: live recognition decides the rate, from OcrConfig.maxFps.
 *
 * Creating a processor fails when Tesseract's language data is missing or unusable, so the caller
 * can report it; a processor that exists can always scan until it is closed.
 */
class OCRProcessor internal constructor(private val library: TesseractLibrary, tessdataDir: String?) {
    /**
     * Loads `eng.traineddata` from the first source [config] and the process offer (see [OcrConfig]).
     *
     * @throws TessdataNotFoundException when no source has the language data.
     * @throws IllegalStateException when Tesseract cannot load it.
     */
    constructor(config: OcrConfig = OcrConfig()) : this(
        JavaCppTesseract,
        findTessdataDirectory(processTessdataSources(config)),
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
            check(api.init(tessdataDir, "eng") == 0) { "Tesseract could not load eng.traineddata" }
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

    /**
     * Scans a BufferedImage for text using Tesseract OCR.
     *
     * Uses thread-safe locking. Preprocesses images to grayscale for better OCR accuracy. Returns
     * null after [close].
     *
     * @param image The BufferedImage frame to scan
     * @return Extracted text if successful, null if no text found, another scan is in progress, or the processor is closed
     */
    fun scanImage(image: BufferedImage): String? {
        if (!lock.tryLock()) return null

        return try {
            if (isClosed) return null

            val grayImage = preprocessImage(image)
            val raster = grayImage.raster
            val pixels = (raster.dataBuffer as DataBufferByte).data
            val bytesPerLine = (raster.sampleModel as ComponentSampleModel).scanlineStride
            // One native call for the whole frame; Tesseract copies the rows into its own image.
            api.setImage(pixels, grayImage.width, grayImage.height, 1, bytesPerLine)
            val result =
                try {
                    recognizeText()
                } finally {
                    api.clear()
                }

            result.takeIf { !it.isNullOrBlank() }
        } catch (e: Exception) {
            CameraKLogger.e("CameraK", "OcrPlugin: Text recognition failed for a frame", e)
            null
        } finally {
            lock.unlock()
        }
    }

    /**
     * Converts a color image to grayscale for improved OCR accuracy.
     *
     * @param image The source image
     * @return A grayscale BufferedImage
     */
    private fun preprocessImage(image: BufferedImage): BufferedImage {
        val grayImage =
            BufferedImage(
                image.width,
                image.height,
                BufferedImage.TYPE_BYTE_GRAY,
            )

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
    fun close() {
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
}

actual suspend fun extractTextFromBitmapImpl(bitmap: ImageBitmap, config: OcrConfig): String =
    extractText(bitmap.toAwtImage()) { OCRProcessor(config) }

/**
 * Thrown when desktop OCR finds no `eng.traineddata` in any source [OcrConfig] lists.
 */
class TessdataNotFoundException(message: String) : IllegalStateException(message)

/** Reads the text in [image] once, with a processor from [newProcessor]. */
internal fun extractText(image: BufferedImage, newProcessor: () -> OCRProcessor): String {
    val ocrProcessor = newProcessor()
    return try {
        ocrProcessor.scanImage(image) ?: ""
    } finally {
        ocrProcessor.close()
    }
}

/**
 * Enables continuous text recognition on the desktop camera controller.
 *
 * Processes camera frames asynchronously and emits detected text via callback.
 *
 * @param cameraController The camera controller providing frames
 * @param config Where to find Tesseract's language data
 * @param onText Callback invoked when text is detected with the extracted text
 * @throws TessdataNotFoundException when no source in [config] has the language data
 */
actual fun startRecognition(
    cameraController: CameraController,
    config: OcrConfig,
    onText: (text: String) -> Unit,
): RecognitionHandle {
    val ocrProcessor = OCRProcessor(config)
    return startRecognition(
        cameraController.frameFlow,
        ocrProcessor::scanImage,
        ocrProcessor::close,
        config.maxFps,
        System::nanoTime,
        onText,
    )
}

/** [startRecognition] at the default rate, [DEFAULT_LIVE_OCR_MAX_FPS]. */
internal fun startRecognition(
    frames: Flow<BufferedImage>,
    scan: (BufferedImage) -> String?,
    close: () -> Unit,
    onText: (text: String) -> Unit,
): RecognitionHandle = startRecognition(frames, scan, close, DEFAULT_LIVE_OCR_MAX_FPS, System::nanoTime, onText)

/**
 * Scans at most [maxFps] of [frames] a second with [scan], and frees the scanner with [close] when
 * the returned handle stops. [clock] reads nanoseconds.
 */
internal fun startRecognition(
    frames: Flow<BufferedImage>,
    scan: (BufferedImage) -> String?,
    close: () -> Unit,
    maxFps: Int,
    clock: () -> Long,
    onText: (text: String) -> Unit,
): RecognitionHandle {
    // Only the collector below reads the gate.
    val gate = FrameRateGate(maxFps)
    val scope = CoroutineScope(Dispatchers.Default)

    // The collector owns the scanner: it is the only caller of scan(), and it closes the scanner in
    // its own finally, after its last scan has returned. ATOMIC so that finally runs even when the
    // handle stops before the collector is dispatched.
    scope.launch(start = CoroutineStart.ATOMIC) {
        try {
            frames.collect { image ->
                if (!gate.tryPass(clock())) return@collect
                scan(image)?.let { text ->
                    withContext(Dispatchers.Main) {
                        onText(text)
                    }
                }
            }
        } finally {
            try {
                close()
            } catch (e: Exception) {
                CameraKLogger.e("CameraK", "OcrPlugin: Failed to release the text scanner", e)
            }
        }
    }

    // Only cancels, so stop() never waits on Tesseract and a second stop() does nothing.
    return RecognitionHandle { scope.cancel() }
}

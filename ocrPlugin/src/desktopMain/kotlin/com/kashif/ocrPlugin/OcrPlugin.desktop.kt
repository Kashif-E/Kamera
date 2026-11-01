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
import java.io.File

/**
 * Desktop implementation of OCR text recognition using Tesseract engine.
 *
 * Processes BufferedImage frames to extract text using Tesseract OCR with
 * thread-safe locking and frame rate throttling for performance.
 *
 * Tesseract initialization is wrapped in a try-catch to gracefully handle missing tessdata files.
 */
class OCRProcessor internal constructor(
    private val library: TesseractLibrary,
    tessdataDir: String?,
    private val clock: () -> Long,
) {
    constructor() : this(JavaCppTesseract, findTessdataDirectory(), System::currentTimeMillis)

    private val api = library.newEngine()
    private val lock = ReentrantLock()
    private var lastProcessTime = 0L
    private val processInterval = 200L
    private var isInitialized = false

    // Guarded by lock: set by close(), after which nothing calls into Tesseract.
    private var isClosed = false

    init {
        try {
            if (tessdataDir == null) {
                CameraKLogger.w("CameraK", "Warning: Could not find tessdata directory. OCR will be disabled.")
                isInitialized = false
            } else if (api.init(tessdataDir, "eng") != 0) {
                CameraKLogger.w("CameraK", "Warning: Could not initialize Tesseract. OCR will be disabled.")
                isInitialized = false
            } else {
                api.setVariable(
                    "tessedit_char_whitelist",
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.,!?",
                )
                api.setPageSegMode(PSM_AUTO)
                isInitialized = true
            }
        } catch (e: Exception) {
            CameraKLogger.w("CameraK", "Warning: Failed to initialize OCR", e)
            isInitialized = false
        }
    }

    /**
     * Scans a BufferedImage for text using Tesseract OCR.
     *
     * Uses thread-safe locking and throttles processing to every 200ms. Preprocesses
     * images to grayscale for better OCR accuracy. Returns null if OCR is not initialized.
     *
     * @param image The BufferedImage frame to scan
     * @return Extracted text if successful, null if no text found, processing is throttled, or OCR is disabled
     */
    fun scanImage(image: BufferedImage): String? {
        if (!isInitialized || !lock.tryLock()) return null

        return try {
            if (isClosed) return null
            val currentTime = clock()
            if (currentTime - lastProcessTime < processInterval) {
                return null
            }

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
            lastProcessTime = currentTime

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

private fun findTessdataDirectory(): String? {
    val possiblePaths =
        listOf(
            File("ocrPlugin/src/desktopMain/kotlin/com/kashif/ocrPlugin").absolutePath,
            File("./ocrPlugin/src/desktopMain/kotlin/com/kashif/ocrPlugin").absolutePath,
            System.getenv("TESSDATA_PREFIX"),
            File(
                System.getProperty("user.dir"),
                "ocrPlugin/src/desktopMain/kotlin/com/kashif/ocrPlugin",
            ).absolutePath,
        )

    return possiblePaths.firstOrNull { path ->
        path != null && File(path).exists() && File("$path/eng.traineddata").exists()
    }
}

actual suspend fun extractTextFromBitmapImpl(bitmap: ImageBitmap): String =
    extractText(bitmap.toAwtImage(), ::OCRProcessor)

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
 * @param onText Callback invoked when text is detected with the extracted text
 */
actual fun startRecognition(cameraController: CameraController, onText: (text: String) -> Unit): RecognitionHandle {
    val ocrProcessor = OCRProcessor()
    return startRecognition(cameraController.frameFlow, ocrProcessor::scanImage, ocrProcessor::close, onText)
}

/** Scans [frames] with [scan] and frees the scanner with [close] when the returned handle stops. */
internal fun startRecognition(
    frames: Flow<BufferedImage>,
    scan: (BufferedImage) -> String?,
    close: () -> Unit,
    onText: (text: String) -> Unit,
): RecognitionHandle {
    val scope = CoroutineScope(Dispatchers.Default)

    // The collector owns the scanner: it is the only caller of scan(), and it closes the scanner in
    // its own finally, after its last scan has returned. ATOMIC so that finally runs even when the
    // handle stops before the collector is dispatched.
    scope.launch(start = CoroutineStart.ATOMIC) {
        try {
            frames.collect { image ->
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

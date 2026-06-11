package com.kashif.ocrPlugin

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import com.kashif.cameraK.controller.CameraController
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bytedeco.leptonica.PIX
import org.bytedeco.leptonica.global.leptonica.pixCreate
import org.bytedeco.leptonica.global.leptonica.pixSetPixel
import org.bytedeco.opencv.global.opencv_text.PSM_AUTO
import org.bytedeco.tesseract.TessBaseAPI
import java.awt.image.BufferedImage
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
class OCRProcessor {
    private val api = TessBaseAPI()
    private val lock = ReentrantLock()
    private var lastProcessTime = 0L
    private val processInterval = 200L
    private var isInitialized = false

    init {
        try {
            val tessdataDir = findTessdataDirectory()
            if (tessdataDir == null) {
                System.err.println("Warning: Could not find tessdata directory. OCR will be disabled.")
                isInitialized = false
            } else if (api.Init(tessdataDir, "eng") != 0) {
                System.err.println("Warning: Could not initialize Tesseract. OCR will be disabled.")
                isInitialized = false
            } else {
                api.SetVariable(
                    "tessedit_char_whitelist",
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.,!?",
                )
                api.SetPageSegMode(PSM_AUTO)
                isInitialized = true
            }
        } catch (e: Exception) {
            System.err.println("Warning: Failed to initialize OCR: ${e.message}")
            isInitialized = false
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
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastProcessTime < processInterval) {
                return null
            }

            val processedImage = preprocessImage(image)
            val pix = convertImageToPix(processedImage)

            api.SetImage(pix)
            val result = api.GetUTF8Text()?.getString()?.trim()
            lastProcessTime = currentTime

            result.takeIf { !it.isNullOrBlank() }
        } catch (e: Exception) {
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

    /**
     * Converts a grayscale BufferedImage to Leptonica PIX format for Tesseract.
     *
     * @param image The grayscale image
     * @return A PIX object suitable for Tesseract processing
     */
    private fun convertImageToPix(image: BufferedImage): PIX {
        val width = image.width
        val height = image.height
        val pix = pixCreate(width, height, 8)

        val raster = image.raster
        val data = raster.dataBuffer as DataBufferByte
        val pixels = data.data

        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = pixels[y * width + x].toInt() and 0xFF
                pixSetPixel(pix, x, y, value)
            }
        }

        return pix
    }

    /**
     * Releases Tesseract resources.
     */
    fun close() {
        api.End()
    }
}

actual suspend fun extractTextFromBitmapImpl(bitmap: ImageBitmap): String {
    val ocrProcessor = OCRProcessor()
    return ocrProcessor.scanImage(bitmap.toAwtImage()) ?: ""
}

/**
 * Enables continuous text recognition on the desktop camera controller.
 *
 * Processes camera frames asynchronously and emits detected text via callback.
 *
 * @param cameraController The camera controller providing frames
 * @param onText Callback invoked when text is detected with the extracted text
 */
actual fun startRecognition(cameraController: CameraController, onText: (text: String) -> Unit) {
    val ocrProcessor = OCRProcessor()
    val scope = CoroutineScope(Dispatchers.Default)

    scope.launch {
        cameraController.frameFlow.collect { image ->
            println("Consumed from OcrPlugin, thread ${Thread.currentThread().name}")
            ocrProcessor.scanImage(image)?.let { text ->
                withContext(Dispatchers.Main) {
                    onText(text)
                }
            }

        }
    }
}

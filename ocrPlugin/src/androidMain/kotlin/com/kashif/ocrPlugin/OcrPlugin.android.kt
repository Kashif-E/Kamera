package com.kashif.ocrPlugin

import android.graphics.Bitmap
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual suspend fun extractTextFromBitmapImpl(bitmap: ImageBitmap, config: OcrConfig): String =
    extractText(bitmap, ::MlKitBitmapTextRecognizer)

/**
 * The ML Kit calls one-shot OCR makes, so a test can stand in for the recognizer and check that
 * every one it creates is closed. [close] frees the recognizer's native model handles.
 */
internal interface BitmapTextRecognizer {
    /** Reads the text in [bitmap]; the task fails with ML Kit's exception. */
    fun recognize(bitmap: Bitmap): Task<String>

    fun close()
}

/** [BitmapTextRecognizer] over ML Kit's Latin text recognizer. */
internal class MlKitBitmapTextRecognizer : BitmapTextRecognizer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun recognize(bitmap: Bitmap): Task<String> =
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).onSuccessTask(DirectExecutor) { Tasks.forResult(it.text) }

    override fun close() = recognizer.close()
}

// The listeners only resume a continuation, so they run on whichever thread completes the task
// instead of being posted to the main thread.
private val DirectExecutor = Executor(Runnable::run)

/**
 * Reads the text in [bitmap] once with a recognizer from [newRecognizer], which is closed before
 * this returns, however it returns.
 */
internal suspend fun extractText(bitmap: ImageBitmap, newRecognizer: () -> BitmapTextRecognizer): String =
    withContext(Dispatchers.Default) {
        try {
            CameraKLogger.d("TextRecognition", "Starting text extraction from bitmap")
            require(bitmap.width > 0 && bitmap.height > 0) { "Invalid bitmap dimensions" }

            val recognizer = newRecognizer()
            try {
                val extractedText = recognizer.recognize(bitmap.asAndroidBitmap()).await()
                CameraKLogger.d("TextRecognition", "Text extracted, length=${extractedText.length}")
                extractedText
            } finally {
                recognizer.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CameraKLogger.e("TextRecognition", "Text extraction failed", e)
            throw e
        }
    }

// Cancelling the caller resumes at once; the task's late result is then dropped.
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener(DirectExecutor) { continuation.resume(it) }
    addOnFailureListener(DirectExecutor) { continuation.resumeWithException(it) }
}

internal fun CameraController.enableTextRecognition(
    onTextRecognized: (String) -> Unit,
    onError: (Exception) -> Unit = logRecognitionError,
): TextRecognitionAnalyzer {
    CameraKLogger.d("TextRecognition", "Configuring text recognition analyzer")

    val analyzer =
        TextRecognitionAnalyzer(
            onTextRecognized = onTextRecognized,
            onError = onError,
        )

    registerImageAnalyzer(analyzer)
    return analyzer
}

/**
 * The ML Kit call live OCR makes on each analysis frame, so a test can stand in for the recognizer
 * and count the frames it is asked to read. [close] frees the recognizer's native model handles.
 */
internal interface LiveTextRecognizer {
    /**
     * Starts reading the text in [frame], or returns null when the frame has no image to read. The
     * task fails with ML Kit's exception. The caller closes [frame] once the task completes.
     */
    fun recognize(frame: ImageProxy): Task<String>?

    fun close()
}

/** [LiveTextRecognizer] over ML Kit's Latin text recognizer. */
internal class MlKitLiveTextRecognizer : LiveTextRecognizer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun recognize(frame: ImageProxy): Task<String>? {
        val mediaImage = frame.image ?: return null
        val image = InputImage.fromMediaImage(mediaImage, frame.imageInfo.rotationDegrees)
        return recognizer.process(image).onSuccessTask(DirectExecutor) { Tasks.forResult(it.text) }
    }

    override fun close() = recognizer.close()
}

private val logRecognitionError: (Exception) -> Unit = { CameraKLogger.e("TextRecognition", "Recognition error", it) }

internal class TextRecognitionAnalyzer(
    private val onTextRecognized: (String) -> Unit,
    private val onError: (Exception) -> Unit = logRecognitionError,
    private val recognizer: LiveTextRecognizer = MlKitLiveTextRecognizer(),
) : ImageAnalysis.Analyzer {
    private val processedTexts = mutableSetOf<String>()

    override fun analyze(imageProxy: ImageProxy) {
        try {
            val task = recognizer.recognize(imageProxy)
            if (task == null) {
                imageProxy.close()
                return
            }

            task
                .addOnSuccessListener { text ->
                    // Avoid duplicate emissions for same text
                    if (text.isNotEmpty() && !processedTexts.contains(text)) {
                        processedTexts.add(text)
                        CameraKLogger.d("TextRecognition", "Text detected: ${text.take(100)}...")
                        onTextRecognized(text)

                        // Clear cache periodically to avoid memory buildup
                        if (processedTexts.size > 50) {
                            processedTexts.clear()
                        }
                    }
                }.addOnFailureListener { exception ->
                    CameraKLogger.e("TextRecognition", "Recognition failed", exception)
                    onError(exception)
                }.addOnCompleteListener {
                    imageProxy.close()
                }
        } catch (e: Exception) {
            CameraKLogger.e("TextRecognition", "Analyzer error", e)
            onError(e)
            imageProxy.close()
        }
    }

    fun shutdown() {
        try {
            recognizer.close()
            processedTexts.clear()
        } catch (e: Exception) {
            CameraKLogger.e("TextRecognition", "Error closing recognizer", e)
        }
    }
}

actual fun startRecognition(
    cameraController: CameraController,
    config: OcrConfig,
    onText: (text: String) -> Unit,
): RecognitionHandle {
    val analyzer = cameraController.enableTextRecognition(onText)
    return RecognitionHandle {
        cameraController.unregisterImageAnalyzer(analyzer)
        analyzer.shutdown() // close the ML Kit recognizer
    }
}

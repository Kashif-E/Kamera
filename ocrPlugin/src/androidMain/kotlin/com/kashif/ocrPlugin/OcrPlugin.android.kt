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

/** Reads the text in [bitmap] once with a recognizer from [newRecognizer]. */
internal suspend fun extractText(bitmap: ImageBitmap, newRecognizer: () -> BitmapTextRecognizer): String =
    withContext(Dispatchers.Default) {
        try {
            suspendCancellableCoroutine { continuation ->
                CameraKLogger.d("TextRecognition", "Starting text extraction from bitmap")

                val recognizer = newRecognizer()

                if (bitmap.width <= 0 || bitmap.height <= 0) {
                    continuation.resumeWithException(IllegalArgumentException("Invalid bitmap dimensions"))
                    return@suspendCancellableCoroutine
                }

                recognizer
                    .recognize(bitmap.asAndroidBitmap())
                    .addOnSuccessListener(DirectExecutor) { extractedText ->
                        CameraKLogger.d(
                            "TextRecognition",
                            "Text extracted successfully: ${extractedText.take(100)}...",
                        )
                        continuation.resume(extractedText) { cause, _, _ ->
                            recognizer.close()
                        }
                    }.addOnFailureListener(DirectExecutor) { exception ->
                        CameraKLogger.e("TextRecognition", "Text extraction failed", exception)
                        continuation.resumeWithException(exception)
                    }

                continuation.invokeOnCancellation {
                    recognizer.close()
                }
            }
        } catch (e: Exception) {
            CameraKLogger.e("TextRecognition", "Error during text extraction", e)
            throw e
        }
    }

internal fun CameraController.enableTextRecognition(
    onTextRecognized: (String) -> Unit,
    onError: (Exception) -> Unit = { CameraKLogger.e("TextRecognition", "Recognition error", it) },
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

internal class TextRecognitionAnalyzer(
    private val onTextRecognized: (String) -> Unit,
    private val onError: (Exception) -> Unit,
) : ImageAnalysis.Analyzer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val processedTexts = mutableSetOf<String>()

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        try {
            val image =
                InputImage.fromMediaImage(
                    mediaImage,
                    imageProxy.imageInfo.rotationDegrees,
                )

            recognizer
                .process(image)
                .addOnSuccessListener { visionText ->
                    val text = visionText.text
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

package com.kashif.ocrPlugin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * OCR plugin for the Compose-first camera API.
 *
 * ```kotlin
 * val scope = rememberCoroutineScope()
 * val ocrPlugin = remember { OcrPlugin(scope) }
 * var recognizedText by remember { mutableStateOf<String?>(null) }
 *
 * val cameraState by rememberCameraKState { stateHolder ->
 *     ocrPlugin.attachToStateHolder(stateHolder)
 * }
 *
 * LaunchedEffect(ocrPlugin) {
 *     ocrPlugin.ocrFlow.receive { text ->
 *         recognizedText = text
 *     }
 * }
 * ```
 */
@Stable
class OcrPlugin(val coroutineScope: CoroutineScope) : CameraKPlugin {
    private var cameraController: CameraController? = null
    private var stateHolder: CameraKStateHolder? = null

    // Buffered (not rendezvous): trySend no longer silently drops when no consumer is parked.
    // Intentionally never closed — closing a val channel would make a later re-attach throw.
    val ocrFlow = Channel<String>(Channel.BUFFERED)
    private var isRecognising = atomic(false)
    private var collectorJob: Job? = null
    private var recognitionHandle: RecognitionHandle? = null

    /**
     * Attaches the plugin to the state holder (new API).
     * Automatically starts OCR when camera becomes ready.
     *
     * @param stateHolder The [CameraKStateHolder] to attach to.
     */
    override fun onAttach(stateHolder: CameraKStateHolder) {
        CameraKLogger.d("CameraK", "OcrPlugin attached (new API)")
        this.stateHolder = stateHolder

        collectorJob =
            stateHolder.pluginScope.launch {
                stateHolder.cameraState
                    .filterIsInstance<CameraKState.Ready>()
                    .collect { readyState ->
                        try {
                            this@OcrPlugin.cameraController = readyState.controller
                            startRecognition()
                        } catch (e: Exception) {
                            CameraKLogger.e("CameraK", "OcrPlugin: Failed to start recognition: ${e.message}")
                            CameraKLogger.e("CameraK", "Unhandled exception", e)
                        }
                    }
            }
    }

    /**
     * Detaches the plugin from the state holder and cleans up resources.
     */
    override fun onDetach() {
        CameraKLogger.d("CameraK", "OcrPlugin detached")
        stopRecognition()
        collectorJob?.cancel()
        collectorJob = null
        // Note: ocrFlow is deliberately NOT closed — the plugin instance can be re-attached.
        this.stateHolder = null
        this.cameraController = null
    }

    /**
     * Convenience method to attach this plugin to a state holder.
     * Use this when manually managing plugin lifecycle.
     *
     * @param stateHolder The state holder to attach to.
     */
    fun attachToStateHolder(stateHolder: CameraKStateHolder) {
        stateHolder.attachPlugin(this)
    }

    /**
     * Extracts text from a bitmap image asynchronously.
     *
     * @param textureImage The image bitmap to extract text from.
     */
    fun extractTextFromBitmap(textureImage: ImageBitmap) = coroutineScope.launch {
        CameraKLogger.d("CameraK", "Starting text extraction from bitmap")
        val extractedText = extractTextFromBitmapImpl(textureImage)
        CameraKLogger.d("CameraK", "Extracted text: $extractedText")
    }

    fun startRecognition() {
        val controller = cameraController ?: return
        // Tear down any previous analyzer/output so re-init doesn't stack recognizers.
        recognitionHandle?.stop()
        isRecognising.value = true
        recognitionHandle = startRecognition(controller) { text ->
            if (isRecognising.value) {
                ocrFlow.trySend(text)
                // emitEvent is suspend → launch on the owned plugin scope (cancelled on shutdown).
                stateHolder?.let { holder ->
                    holder.pluginScope.launch { holder.emitEvent(CameraKEvent.TextRecognized(text)) }
                }
            }
        }
    }

    fun stopRecognition() {
        isRecognising.value = false
        recognitionHandle?.stop()
        recognitionHandle = null
    }
}

/**
 * Handle to active recognition. [stop] removes the underlying platform analyzer/output so OCR
 * stops consuming frames — the plugin keeps no other way to tear that registration down.
 */
fun interface RecognitionHandle {
    fun stop()
}

expect suspend fun extractTextFromBitmapImpl(bitmap: ImageBitmap): String

expect fun startRecognition(cameraController: CameraController, onText: (text: String) -> Unit): RecognitionHandle

@Composable
fun rememberOcrPlugin(coroutineScope: CoroutineScope = rememberCoroutineScope()): OcrPlugin = remember {
    OcrPlugin(coroutineScope)
}

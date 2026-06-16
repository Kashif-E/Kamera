package com.kashif.ocrPlugin
import com.kashif.cameraK.utils.CameraKLogger

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.plugins.CameraPlugin
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * OCR plugin that works with both old and new camera APIs.
 *
 * **New Compose-first API usage:**
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
 *
 * **Legacy API usage:**
 * ```kotlin
 * val ocrPlugin = rememberOcrPlugin()
 *
 * CameraPreview(
 *     onCameraControllerReady = { controller ->
 *         ocrPlugin.initialize(controller)
 *         ocrPlugin.startRecognition()
 *     }
 * )
 * ```
 */
@Stable
class OcrPlugin(val coroutineScope: CoroutineScope) :
    CameraPlugin,
    CameraKPlugin {
    private var cameraController: CameraController? = null
    private var stateHolder: CameraKStateHolder? = null
    val ocrFlow = Channel<String>()
    private var isRecognising = atomic(false)
    private var collectorJob: kotlinx.coroutines.Job? = null

    /**
     * Initializes the plugin with the camera controller (legacy API).
     *
     * @param cameraController The [CameraController] instance to use for OCR.
     */
    override fun initialize(cameraController: CameraController) {
        CameraKLogger.d("CameraK", "OcrPlugin initialized (legacy API)")
        this.cameraController = cameraController
    }

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
                            CameraKLogger.e("CameraK", "error", e)
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
        ocrFlow.close()
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
        isRecognising.value = true
        cameraController?.let { controller ->
            startRecognition(controller) { text ->
                if (isRecognising.value) {
                    ocrFlow.trySend(text)
                    // Emit event to state holder if available (new API)
                    coroutineScope.launch {
                        stateHolder?.emitEvent(CameraKEvent.TextRecognized(text))
                    }
                }
            }
        }
    }

    fun stopRecognition() {
        isRecognising.value = false
    }
}

expect suspend fun extractTextFromBitmapImpl(bitmap: ImageBitmap): String

expect fun startRecognition(cameraController: CameraController, onText: (text: String) -> Unit)

@Composable
fun rememberOcrPlugin(coroutineScope: CoroutineScope = rememberCoroutineScope()): OcrPlugin = remember {
    OcrPlugin(coroutineScope)
}

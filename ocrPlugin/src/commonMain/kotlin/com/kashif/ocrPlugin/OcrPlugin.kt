package com.kashif.ocrPlugin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.utils.InternalCameraKApi
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * OCR plugin for the Compose-first camera API. On desktop, [config] says where Tesseract's language
 * data is (see [OcrConfig]).
 *
 * @sample com.kashif.ocrPlugin.RecognizeLiveTextSample
 */
@Stable
class OcrPlugin internal constructor(
    val coroutineScope: CoroutineScope,
    val config: OcrConfig,
    private val recognizeBitmap: suspend (ImageBitmap, OcrConfig) -> String = ::extractTextFromBitmapImpl,
    private val recognitionDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val startRecognizer: (CameraController, OcrConfig, (String) -> Unit) -> RecognitionHandle,
) : CameraKPlugin {
    constructor(coroutineScope: CoroutineScope, config: OcrConfig = OcrConfig()) : this(
        coroutineScope,
        config,
        startRecognizer = { controller, recognizerConfig, onText ->
            com.kashif.ocrPlugin.startRecognition(controller, recognizerConfig, onText)
        },
    )

    private var cameraController: CameraController? = null
    private var stateHolder: CameraKStateHolder? = null

    private val recognisedText =
        MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Text recognised in the live camera stream. Every collector receives each result; a collector
     * that falls behind gets the latest one, as with the QR and analyzer plugins.
     */
    val textFlow: SharedFlow<String> = recognisedText.asSharedFlow()

    /**
     * The same text as [textFlow], through a channel that any holder can send to or close and whose
     * collectors split the results between them. It is still fed so existing code keeps working.
     */
    @Deprecated(
        "A channel lets consumers send, close and split results. Collect textFlow instead.",
        ReplaceWith("textFlow"),
    )
    val ocrFlow = Channel<String>(Channel.BUFFERED)
    private var isRecognising = atomic(false)

    // Texts bound for the state holder's event bus. One forwarder per attachment emits them, so a
    // slow events collector holds back one result instead of one suspended coroutine per frame, and
    // a collector that falls behind gets the latest one.
    private val busTexts =
        MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
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
                launch(start = CoroutineStart.UNDISPATCHED) {
                    busTexts.collect { stateHolder.emitEvent(CameraKEvent.TextRecognized(it)) }
                }
                stateHolder.cameraState
                    .filterIsInstance<CameraKState.Ready>()
                    .collect { readyState ->
                        try {
                            this@OcrPlugin.cameraController = readyState.controller
                            startRecognition()
                        } catch (e: Exception) {
                            CameraKLogger.e("CameraK", "OcrPlugin: Failed to start recognition", e)
                            stateHolder.emitEvent(
                                @OptIn(InternalCameraKApi::class)
                                CameraKEvent.PluginFailed(
                                    this@OcrPlugin::class.simpleName ?: "OcrPlugin",
                                    CameraKError.Internal(e),
                                ),
                            )
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
        // Note: the deprecated ocrFlow is deliberately NOT closed — the plugin instance can be re-attached.
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
     * Reads the text in [bitmap] once and returns it to the caller. The recognizer runs on
     * [Dispatchers.Default], never on the caller's thread, so it is safe to call from Main. The
     * text is not logged.
     *
     * @return The recognised text, or "" when there is none.
     */
    suspend fun recognize(bitmap: ImageBitmap): String {
        val text = withContext(recognitionDispatcher) { recognizeBitmap(bitmap, config) }
        CameraKLogger.d("CameraK", "OcrPlugin: one-shot text recognition finished, length=${text.length}")
        return text
    }

    /**
     * Reads the text in [textureImage] in the plugin's constructor scope. The returned [Job] carries
     * no result, so the text reaches nobody: use [recognize].
     */
    @Deprecated(
        "The text never reaches the caller. Call the suspending recognize(bitmap), which returns it.",
        ReplaceWith("recognize(textureImage)"),
    )
    fun extractTextFromBitmap(textureImage: ImageBitmap): Job = coroutineScope.launch { recognize(textureImage) }

    fun startRecognition() {
        val controller = cameraController ?: return
        // Tear down any previous analyzer/output so re-init doesn't stack recognizers.
        recognitionHandle?.stop()
        isRecognising.value = true
        recognitionHandle = startRecognizer(controller, config) { text ->
            if (isRecognising.value) {
                recognisedText.tryEmit(text)
                // The deprecated channel may be full or closed by a consumer; textFlow has the text either way.
                @Suppress("DEPRECATION")
                ocrFlow.trySend(text)
                busTexts.tryEmit(text)
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

expect suspend fun extractTextFromBitmapImpl(bitmap: ImageBitmap, config: OcrConfig = OcrConfig()): String

expect fun startRecognition(
    cameraController: CameraController,
    config: OcrConfig = OcrConfig(),
    onText: (text: String) -> Unit,
): RecognitionHandle

@Composable
fun rememberOcrPlugin(
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    config: OcrConfig = OcrConfig(),
): OcrPlugin = remember(config) {
    OcrPlugin(coroutineScope, config)
}

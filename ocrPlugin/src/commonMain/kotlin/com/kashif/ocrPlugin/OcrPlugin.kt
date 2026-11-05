package com.kashif.ocrPlugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.plugin.CameraKPluginContext
import com.kashif.cameraK.plugin.PluginStatus
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraKPlugin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Recognizes text: live, in the holder's analysis frames while a session is Ready, and once, in a
 * still, with [recognize].
 *
 * Live recognition reads at most [OcrConfig.maxFps] frames a second through the holder's shared
 * frame source, in either analysis format, and adds no camera output of its own. Each frame it
 * reads is turned to grayscale, cut to [OcrConfig.region], and handed to the platform's recognizer
 * with the frame's rotation; frames that arrive while a recognition runs are skipped, never queued.
 * What it reads is emitted on [results]. Recognized text is never logged.
 *
 * @sample com.kashif.ocrPlugin.RecognizeLiveTextSample
 * @property config How live text is recognized.
 */
public class OcrPlugin internal constructor(
    public val config: OcrConfig,
    private val recognizers: TextRecognizerFactory,
    private val recognitionDispatcher: CoroutineDispatcher,
) : CameraKPlugin {
    /** A plugin for [config] over this platform's recognizer: ML Kit, Vision or Tesseract. */
    public constructor(
        config: OcrConfig = OcrConfig(),
    ) : this(config, platformTextRecognizers(config), Dispatchers.Default)

    internal constructor(config: OcrConfig, recognizers: TextRecognizerFactory) :
        this(config, recognizers, Dispatchers.Default)

    private val paused = MutableStateFlow(false)
    private val _status = MutableStateFlow<PluginStatus>(PluginStatus.Idle)

    /**
     * Running while live recognition holds a frame registration that is receiving frames; Paused
     * after [pause], or while the registration waits for a frame output; Idle with no Ready session
     * or when detached; `Failed` while the current attachment's context has a failure, such as a
     * live recognizer that could not start.
     */
    public val status: StateFlow<PluginStatus> = _status.asStateFlow()

    private val _results = MutableSharedFlow<RecognizedText>(
        extraBufferCapacity = RESULTS_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Text read in live frames, one value per frame that holds text. Every collector receives each
     * value; a collector that falls behind loses the oldest first, and never holds up recognition.
     */
    public val results: Flow<RecognizedText> = _results.asSharedFlow()

    override fun onAttach(context: CameraKPluginContext): Unit = TODO("OC': live recognition through the frame source")

    override fun onDetach() {
        _status.value = PluginStatus.Idle
    }

    /** Stops live recognition: the plugin closes its frame registration and holds no frames. */
    public fun pause(): Unit = TODO("OC': pause")

    /** Starts live recognition again after [pause], at once if a session is Ready. */
    public fun resume(): Unit = TODO("OC': resume")

    /**
     * Reads the text in [image] once, at [level], off the caller's thread, and releases the
     * recognizer's resources however it ends. The text is not logged.
     *
     * @return the text, empty when there is none; `Failure(Internal)` carrying the platform's
     *   throwable when the image cannot be read or the recognizer fails. It never throws for either.
     * @sample com.kashif.ocrPlugin.recognizeStillSample
     */
    public suspend fun recognize(
        image: CapturedImage,
        level: OcrLevel = OcrLevel.ACCURATE,
    ): CameraKResult<RecognizedText> =
        TODO("OC': recognize(image) on $recognizers, $recognitionDispatcher, ${image.path}, $level")

    private companion object {
        const val TAG = "CameraK"
        const val RESULTS_BUFFER = 16
    }
}

/** An [OcrPlugin] for [config], remembered until [config] changes. */
@Composable
public fun rememberOcrPlugin(config: OcrConfig = OcrConfig()): OcrPlugin = remember(config) { OcrPlugin(config) }

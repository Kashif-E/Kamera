package com.kashif.ocrplugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.frames.FrameAnalyzer
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FrameRegistration
import com.kashif.cameraK.frames.FrameSpec
import com.kashif.cameraK.frames.isFatalAnalyzerFailure
import com.kashif.cameraK.plugin.CameraKPluginContext
import com.kashif.cameraK.plugin.PluginStatus
import com.kashif.cameraK.plugin.launchPerSession
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.utils.InternalCameraKApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * @sample com.kashif.ocrplugin.RecognizeLiveTextSample
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

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onAttach(context: CameraKPluginContext) {
        val registration = MutableStateFlow<FrameRegistration?>(null)
        context.scope.launch {
            val own = registration.flatMapLatest { current ->
                current?.active?.map { active -> if (active) PluginStatus.Running else PluginStatus.Paused }
                    ?: flowOf(PluginStatus.Idle)
            }
            combine(context.failure, paused, own) { failure, isPaused, ownStatus ->
                when {
                    failure != null -> PluginStatus.Failed(failure)
                    isPaused -> PluginStatus.Paused
                    else -> ownStatus
                }
            }.collect { _status.value = it }
        }
        context.launchPerSession {
            paused.collectLatest { isPaused ->
                if (!isPaused) recognizeLive(context, registration)
            }
        }
    }

    override fun onDetach() {
        _status.value = PluginStatus.Idle
    }

    /** Stops live recognition: the plugin closes its frame registration and holds no frames. */
    public fun pause() {
        paused.value = true
    }

    /** Starts live recognition again after [pause], at once if a session is Ready. */
    public fun resume() {
        paused.value = false
    }

    /**
     * Reads the text in [image] once, at [level], off the caller's thread, and releases the
     * recognizer's resources however it ends. The text is not logged.
     *
     * @return the text, empty when there is none; `Failure(Internal)` carrying the platform's
     *   throwable when the image cannot be read or the recognizer fails. It never throws for either.
     * @sample com.kashif.ocrplugin.recognizeStillSample
     */
    @OptIn(InternalCameraKApi::class)
    public suspend fun recognize(
        image: CapturedImage,
        level: OcrLevel = OcrLevel.ACCURATE,
    ): CameraKResult<RecognizedText> = withContext(recognitionDispatcher) {
        try {
            val recognizer = recognizers.still(level)
            val blocks =
                try {
                    recognizer.recognize(image.path)
                } finally {
                    releaseStill(recognizer)
                }
            val text = recognizedText(blocks.map { TextBlock(it.text, it.bounds, it.confidence) }, 0L)
            CameraKLogger.d(TAG, "OcrPlugin: one-shot text recognition finished, length=${text.text.length}")
            CameraKResult.Success(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (e.isFatalAnalyzerFailure()) throw e
            CameraKLogger.e(TAG, "OcrPlugin: one-shot text recognition failed", e)
            CameraKResult.Failure(CameraKError.Internal(e))
        }
    }

    private fun releaseStill(recognizer: StillTextRecognizer) {
        try {
            recognizer.close()
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "OcrPlugin: releasing the one-shot text recognizer failed", e)
        }
    }

    // One session's live recognition, until the session ends or the plugin is paused. A recognizer
    // that cannot start throws into the context's scope, which marks the plugin Failed.
    private suspend fun recognizeLive(
        context: CameraKPluginContext,
        registration: MutableStateFlow<FrameRegistration?>,
    ) {
        val lease = RecognizerLease(recognizers.live(config.level))
        try {
            context.frames.register(frameSpec(), analyzer(lease)).use { current ->
                registration.value = current
                try {
                    awaitCancellation()
                } finally {
                    registration.value = null
                }
            }
        } finally {
            lease.closeWhenIdle()
        }
    }

    private fun frameSpec() = FrameSpec(
        maxFps = config.maxFps,
        maxRetained = 0,
        acceptedFormats = setOf(FrameFormat.YUV_420, FrameFormat.RGBA_8888),
    )

    private fun analyzer(lease: RecognizerLease): FrameAnalyzer {
        val luma = FrameLuma(config.region)
        var outsideLogged = false
        return FrameAnalyzer { frame ->
            val area = luma.read(frame)
            if (area == null) {
                if (!outsideLogged) {
                    outsideLogged = true
                    CameraKLogger.w(TAG, "OcrPlugin: the region lies outside the frame's crop, so no text is read")
                }
                return@FrameAnalyzer
            }
            val found =
                try {
                    lease.use { it.recognize(area.image) } ?: return@FrameAnalyzer
                } catch (e: Exception) {
                    CameraKLogger.e(TAG, "OcrPlugin: live text recognition failed for a frame", e)
                    return@FrameAnalyzer
                }
            val blocks = found.filter { it.text.isNotBlank() }
                .map { TextBlock(it.text, it.bounds?.let(area::toFrame), it.confidence) }
            if (blocks.isEmpty()) return@FrameAnalyzer
            val text = recognizedText(blocks, frame.timestampNanos)
            CameraKLogger.d(TAG, "OcrPlugin: text recognised in a frame, length=${text.text.length}")
            _results.tryEmit(text)
        }
    }

    private fun recognizedText(blocks: List<TextBlock>, timestampNanos: Long) =
        RecognizedText(blocks.joinToString("\n") { it.text }, blocks, timestampNanos)

    private companion object {
        const val TAG = "CameraK"
        const val RESULTS_BUFFER = 16
    }
}

/** An [OcrPlugin] for [config], remembered until [config] changes. */
@Composable
public fun rememberOcrPlugin(config: OcrConfig = OcrConfig()): OcrPlugin = remember(config) { OcrPlugin(config) }

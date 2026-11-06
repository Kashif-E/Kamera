package com.kashif.analyzerplugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FrameSpec
import com.kashif.cameraK.plugin.CameraKPluginContext
import com.kashif.cameraK.plugin.PluginStatus
import com.kashif.cameraK.plugin.launchPerSession
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Hands the app the camera's analysis frames as JPEGs, for models that take encoded images.
 *
 * The plugin reads frames from the holder's frame source and adds no camera output of its own. It
 * registers only while [results] has a collector, the camera is Ready and the plugin is not
 * [pause]d, so with no collector no frame is copied or encoded. Frames arrive at most
 * [AnalyzerConfig.maxFps] a second, scaled down to [AnalyzerConfig.maxDimension].
 *
 * @property config How frames are encoded.
 */
public class AnalyzerPlugin internal constructor(
    public val config: AnalyzerConfig,
    private val encoderFactory: (AnalyzerConfig) -> FrameEncoder,
    private val acceptedFormats: Set<FrameFormat> = ENCODED_FORMATS,
) : CameraKPlugin {
    /** An analyzer that encodes frames as [config] says, with the platform's encoder. */
    public constructor(config: AnalyzerConfig = AnalyzerConfig()) : this(config, ::platformFrameEncoder)

    private val _status = MutableStateFlow<PluginStatus>(PluginStatus.Idle)

    /**
     * Running while frames are being encoded; Paused while [pause]d or while the frame source waits
     * for its output; Idle with no Ready session or no collector of [results]; and `Failed` while
     * the current attachment has failed: its registration was rejected or the encoder threw.
     */
    public val status: StateFlow<PluginStatus> = _status.asStateFlow()

    private val _results = MutableSharedFlow<EncodedFrame>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * The encoded frames. Encoding runs only while this has a collector; a collector that falls
     * behind gets the latest frame and loses the ones before it.
     */
    public val results: Flow<EncodedFrame> = _results.asSharedFlow()

    private val paused = MutableStateFlow(false)

    // What this plugin is doing for the current session: Idle, Running, or Paused while its
    // registration waits for a frame output. The app's pause and the attachment's failure are
    // applied over it in onAttach.
    private val working = MutableStateFlow<PluginStatus>(PluginStatus.Idle)

    override fun onAttach(context: CameraKPluginContext) {
        context.scope.launch {
            combine(context.failure, paused, working) { failure, isPaused, own ->
                when {
                    failure != null -> PluginStatus.Failed(failure)
                    isPaused -> PluginStatus.Paused
                    else -> own
                }
            }.collect { _status.value = it }
        }
        context.launchPerSession {
            combine(paused, _results.subscriptionCount, context.failure) { isPaused, collectors, failure ->
                !isPaused && collectors > 0 && failure == null
            }.distinctUntilChanged().collectLatest { wanted ->
                if (wanted) encodeFrames(context)
            }
        }
    }

    override fun onDetach() {
        working.value = PluginStatus.Idle
        _status.value = if (paused.value) PluginStatus.Paused else PluginStatus.Idle
    }

    /** Stops encoding and closes the frame registration, until [resume]. */
    public fun pause() {
        paused.value = true
    }

    /** Encodes again after [pause], once the camera is Ready and [results] has a collector. */
    public fun resume() {
        paused.value = false
    }

    // Holds one registration until cancelled: by the session ending, a pause, the last collector
    // leaving, or a failure. Each registration gets its own encoder, so an analyze call still
    // finishing on a closed registration's worker never shares buffers with the next one.
    private suspend fun encodeFrames(context: CameraKPluginContext) {
        val encoder = encoderFactory(config)
        val failed = atomic(false)
        val registration = context.frames.register(config.frameSpec(acceptedFormats)) { frame ->
            if (failed.value) return@register
            val encoded = try {
                encoder.encode(frame)
            } catch (e: Exception) {
                failed.value = true
                CameraKLogger.e(TAG, "Encoding an analysis frame failed; the analyzer stops", e)
                context.reportFailure(CameraKError.Internal(e))
                return@register
            }
            if (encoded == null) {
                CameraKLogger.w(TAG, "The platform could not encode an analysis frame; the frame is skipped")
            } else {
                _results.tryEmit(encoded)
            }
        }
        try {
            registration.active.collect { active ->
                working.value = if (active) PluginStatus.Running else PluginStatus.Paused
            }
        } finally {
            registration.close()
            working.value = PluginStatus.Idle
        }
    }

    internal companion object {
        private const val TAG = "AnalyzerPlugin"

        /** The analyzer converts either session format to colour itself. */
        val ENCODED_FORMATS: Set<FrameFormat> = setOf(FrameFormat.YUV_420, FrameFormat.RGBA_8888)
    }
}

/**
 * The registration the analyzer asks for: asynchronous, so a slow encode delays no other analyzer;
 * retaining nothing, because it encodes inside `analyze` from the copied planes; at [AnalyzerConfig.maxFps].
 */
internal fun AnalyzerConfig.frameSpec(acceptedFormats: Set<FrameFormat>): FrameSpec =
    FrameSpec(maxFps = maxFps, maxRetained = 0, synchronous = false, acceptedFormats = acceptedFormats)

/** The same analyzer across recompositions while [config] is unchanged. */
@Composable
public fun rememberAnalyzerPlugin(config: AnalyzerConfig = AnalyzerConfig()): AnalyzerPlugin =
    remember(config) { AnalyzerPlugin(config) }

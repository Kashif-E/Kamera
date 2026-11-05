package com.kashif.analyzerPlugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FrameSpec
import com.kashif.cameraK.plugin.CameraKPluginContext
import com.kashif.cameraK.plugin.PluginStatus
import com.kashif.cameraK.state.CameraKPlugin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

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

    override fun onAttach(context: CameraKPluginContext) {
        TODO("AN': onAttach")
    }

    override fun onDetach() {
        TODO("AN': onDetach")
    }

    /** Stops encoding and closes the frame registration, until [resume]. */
    public fun pause() {
        TODO("AN': pause")
    }

    /** Encodes again after [pause], once the camera is Ready and [results] has a collector. */
    public fun resume() {
        TODO("AN': resume")
    }

    internal companion object {
        /** The analyzer converts either session format to colour itself. */
        val ENCODED_FORMATS: Set<FrameFormat> = setOf(FrameFormat.YUV_420, FrameFormat.RGBA_8888)
    }
}

/**
 * The registration the analyzer asks for: asynchronous, so a slow encode delays no other analyzer;
 * retaining nothing, because it encodes inside `analyze` from the copied planes; at [AnalyzerConfig.maxFps].
 */
internal fun AnalyzerConfig.frameSpec(acceptedFormats: Set<FrameFormat>): FrameSpec = TODO("AN': frameSpec")

/** The same analyzer across recompositions while [config] is unchanged. */
@Composable
public fun rememberAnalyzerPlugin(config: AnalyzerConfig = AnalyzerConfig()): AnalyzerPlugin =
    remember(config) { AnalyzerPlugin(config) }

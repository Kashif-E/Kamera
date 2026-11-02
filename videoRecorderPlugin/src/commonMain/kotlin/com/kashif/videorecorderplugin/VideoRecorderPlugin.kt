package com.kashif.videorecorderplugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Plugin for video recording with configurable quality, audio, and duration limits.
 *
 * Wraps the core [CameraKStateHolder] recording API with:
 * - Pre-configured [VideoConfiguration] settings
 * - Auto-activation when camera becomes ready
 * - Recording event forwarding via [recordingEvents]
 *
 * Usage:
 * ```kotlin
 * val videoPlugin = rememberVideoRecorderPlugin(
 *     config = VideoConfiguration(
 *         quality = VideoQuality.FHD,
 *         enableAudio = true,
 *         maxDurationMs = 60_000L,
 *     )
 * )
 *
 * val cameraState by rememberCameraKState(setupPlugins = { stateHolder ->
 *     stateHolder.attachPlugin(videoPlugin)
 * })
 *
 * // Start recording
 * videoPlugin.startRecording()
 *
 * // Stop recording
 * videoPlugin.stopRecording()
 * ```
 */
@Stable
class VideoRecorderPlugin(val config: VideoConfiguration = VideoConfiguration()) : CameraKPlugin {

    private var stateHolder: CameraKStateHolder? = null
    private var collectorJob: Job? = null

    // Forwarded with tryEmit from inside the state holder's events collector, so a slow collector of
    // recordingEvents never holds back the holder's event bus. Unbounded rather than lossy: these are
    // a few events per recording, and RecordingStopped carries the only copy of the file's path.
    private val _recordingEvents = MutableSharedFlow<CameraKEvent>(extraBufferCapacity = Channel.UNLIMITED)

    /**
     * Observable stream of recording-related events.
     * Emits [CameraKEvent.RecordingStarted], [CameraKEvent.RecordingStopped],
     * and [CameraKEvent.RecordingMaxDurationReached].
     */
    val recordingEvents: SharedFlow<CameraKEvent> = _recordingEvents.asSharedFlow()

    override fun onAttach(stateHolder: CameraKStateHolder) {
        this.stateHolder = stateHolder

        // Forward recording events from the state holder
        collectorJob = stateHolder.pluginScope.launch {
            stateHolder.events.collect { event ->
                when (event) {
                    is CameraKEvent.RecordingStarted,
                    is CameraKEvent.RecordingStopped,
                    is CameraKEvent.RecordingFailed,
                    is CameraKEvent.RecordingMaxDurationReached,
                    -> _recordingEvents.tryEmit(event)

                    else -> { /* ignore non-recording events */ }
                }
            }
        }
    }

    override fun onDetach() {
        // Stop recording if active before detaching
        if (isRecording) {
            stateHolder?.stopRecording()
        }
        collectorJob?.cancel()
        collectorJob = null
        stateHolder = null
    }

    /**
     * Starts recording using the plugin's configured settings.
     *
     * @param outputDirectory Optional override for the output directory.
     *                        If null, uses the directory from [config].
     */
    fun startRecording(outputDirectory: String? = null) {
        val effectiveConfig = if (outputDirectory != null) {
            config.copy(outputDirectory = outputDirectory)
        } else {
            config
        }
        stateHolder?.startRecording(effectiveConfig)
    }

    /**
     * Stops the active recording.
     */
    fun stopRecording() {
        stateHolder?.stopRecording()
    }

    /**
     * Pauses the active recording.
     */
    fun pauseRecording() {
        stateHolder?.pauseRecording()
    }

    /**
     * Resumes a paused recording.
     */
    fun resumeRecording() {
        stateHolder?.resumeRecording()
    }

    /**
     * Whether a recording is currently active.
     */
    val isRecording: Boolean
        get() = stateHolder?.uiState?.value?.isRecording ?: false

    /**
     * Whether the recording is currently paused.
     */
    val isPaused: Boolean
        get() = stateHolder?.uiState?.value?.isPaused ?: false

    /**
     * Current recording duration in milliseconds.
     */
    val recordingDurationMs: Long
        get() = stateHolder?.uiState?.value?.recordingDurationMs ?: 0L
}

/**
 * Creates and remembers a [VideoRecorderPlugin] instance.
 *
 * @param config Recording configuration.
 */
@Composable
fun rememberVideoRecorderPlugin(config: VideoConfiguration = VideoConfiguration()): VideoRecorderPlugin =
    remember(config) {
        VideoRecorderPlugin(config)
    }

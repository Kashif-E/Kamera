package org.company.app

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoCaptureResult
import com.kashif.cameraK.video.VideoConfiguration
import com.kashif.cameraK.video.VideoQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** The two 1.x controller calls the Sample records with. */
internal interface SampleRecorder {
    suspend fun startRecording(configuration: VideoConfiguration): String

    suspend fun stopRecording(): VideoCaptureResult
}

internal fun CameraController.asSampleRecorder(): SampleRecorder {
    val controller = this
    return object : SampleRecorder {
        override suspend fun startRecording(configuration: VideoConfiguration): String =
            controller.startRecording(configuration)

        override suspend fun stopRecording(): VideoCaptureResult = controller.stopRecording()
    }
}

/**
 * The Sample's video recording: Full HD with audio, up to five minutes. It records on the 1.x
 * controller until the engines record (A3', I3', D3'); LC' moves it onto the holder.
 *
 * The 1.x controller reports no end of its own, so the Sample stops the recording at
 * [VideoConfiguration.maxDuration] itself, as the 1.x holder did; on Android and iOS the platform
 * stops there too, and the stop collects its result. A recording the platform ends with an error is
 * still shown until the next tap, whose stop reports it.
 *
 * @param scope Where a recording's own work runs, so it outlives the tap that started it.
 * @param timeSource The clock of the elapsed time shown while recording.
 */
internal class RecordingFeature(
    private val scope: CoroutineScope,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    val configuration = VideoConfiguration(
        quality = VideoQuality.FHD,
        enableAudio = true,
        maxDuration = 5.minutes,
    )

    private val transitions = Mutex()

    // The elapsed time shown while recording: the Sample's own, from when the recording started.
    private var startedAt: TimeMark? = null

    // Stops the recording at the configured limit.
    private var limit: Job? = null

    // Counts the recordings started, so a limit acts only on its own.
    private var started = 0

    private val _isRecording = MutableStateFlow(false)

    /** Whether a recording runs. */
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /** The recorded time, or 0 when nothing is recording. */
    val durationMs: Long get() = startedAt?.elapsedNow()?.inWholeMilliseconds ?: 0L

    /** Starts a recording on [recorder], or stops the one running. */
    suspend fun toggleRecording(recorder: SampleRecorder) {
        transitions.withLock { if (_isRecording.value) stop(recorder) else start(recorder) }
    }

    private suspend fun start(recorder: SampleRecorder) {
        try {
            recorder.startRecording(configuration)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Starting a recording failed", e)
            return
        }
        startedAt = timeSource.markNow()
        _isRecording.value = true
        println("Recording started")
        val recording = ++started
        configuration.maxDuration?.let { maxDuration ->
            limit = scope.launch {
                delay(maxDuration)
                transitions.withLock {
                    if (_isRecording.value && started == recording) {
                        limit = null
                        stop(recorder)
                    }
                }
            }
        }
    }

    // The file is finalized even when the tap's coroutine goes away.
    private suspend fun stop(recorder: SampleRecorder) {
        limit?.cancel()
        limit = null
        val result = withContext(NonCancellable) { recorder.stopRecording() }
        startedAt = null
        _isRecording.value = false
        when (result) {
            is VideoCaptureResult.Success -> println("Recording saved: ${result.filePath}")
            is VideoCaptureResult.Error -> CameraKLogger.e(TAG, "The recording failed", result.exception)
        }
    }

    private companion object {
        const val TAG = "Sample"
    }
}

@Composable
internal fun rememberRecordingFeature(): RecordingFeature {
    val scope = rememberCoroutineScope()
    return remember(scope) { RecordingFeature(scope) }
}

/** While [isRecording], publishes the recording's elapsed time to [onDurationChange] four times a second. */
@Composable
internal fun RecordingEffects(recording: RecordingFeature, isRecording: Boolean, onDurationChange: (Long) -> Unit) {
    LaunchedEffect(isRecording) {
        while (isRecording) {
            onDurationChange(recording.durationMs)
            delay(250L)
        }
        onDurationChange(0L)
    }
}

@Composable
internal fun RecordingTimerHud(modifier: Modifier = Modifier, durationMs: Long) {
    val totalSeconds = (durationMs / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val timeText = "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"

    val infiniteTransition = rememberInfiniteTransition()
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotAlpha",
    )

    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(Red.copy(alpha = dotAlpha), CircleShape),
        )
        Text(
            text = timeText,
            color = Red,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

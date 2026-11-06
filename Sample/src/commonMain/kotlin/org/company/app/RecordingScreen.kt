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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.compose.rememberRecordingElapsed
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.recording.RecordingState
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import com.kashif.cameraK.video.VideoQuality
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** The holder's recording, as the Sample uses it. */
internal interface SampleRecorder {
    val recordingState: StateFlow<RecordingState>

    suspend fun startRecording(configuration: VideoConfiguration): CameraKResult<RecordingState.Recording>

    suspend fun stopRecording(): CameraKResult<RecordedVideo>
}

internal fun CameraKStateHolder.asSampleRecorder(): SampleRecorder {
    val holder = this
    return object : SampleRecorder {
        override val recordingState: StateFlow<RecordingState> get() = holder.recordingState

        override suspend fun startRecording(
            configuration: VideoConfiguration,
        ): CameraKResult<RecordingState.Recording> = holder.startRecording(configuration)

        override suspend fun stopRecording(): CameraKResult<RecordedVideo> = holder.stopRecording()
    }
}

/** Whether a recording is under way, from its start until it has finished. */
internal fun RecordingState.isLive(): Boolean = when (this) {
    RecordingState.Idle, is RecordingState.Finished -> false
    is RecordingState.Starting,
    is RecordingState.Recording,
    is RecordingState.Paused,
    is RecordingState.Stopping,
    -> true
}

/**
 * The time the top bar shows: [holderElapsed], the holder's elapsed time from
 * `rememberRecordingElapsed`, while a recording is live, and zero otherwise, so a finished
 * recording's last tick does not stay on screen.
 */
internal fun RecordingState.shownDurationMs(holderElapsed: Duration): Long =
    if (isLive()) holderElapsed.inWholeMilliseconds else 0L

/**
 * The recorded time the top bar shows for [holder]'s recording in [recordingState]: the holder's
 * elapsed time from `rememberRecordingElapsed`, through [shownDurationMs].
 */
@Composable
internal fun rememberShownRecordingDurationMs(holder: CameraKStateHolder, recordingState: RecordingState): Long =
    recordingState.shownDurationMs(rememberRecordingElapsed(holder).value)

/**
 * The Sample's video recording on the holder: Full HD with audio, up to five minutes.
 *
 * The platform ends a recording at [VideoConfiguration.maxDuration], and the holder's
 * [SampleRecorder.recordingState] says so, as it does for a recording the platform ends with an
 * error. [reportEnds] turns every end, whoever caused it, into a [notices] line for the screen.
 */
internal class RecordingFeature(private val recorder: SampleRecorder) {
    val configuration = VideoConfiguration {
        quality = VideoQuality.FHD
        enableAudio = true
        maxDuration = 5.minutes
    }

    // Two quick taps start and then stop, rather than starting twice.
    private val taps = Mutex()

    private val _notices = Channel<String>(Channel.BUFFERED)

    /** One line per recording that ended or did not start, for the screen to show. */
    val notices: Flow<String> = _notices.receiveAsFlow()

    /** The holder's recording state. */
    val state: StateFlow<RecordingState> get() = recorder.recordingState

    /** Starts a recording, or stops the one under way. */
    suspend fun toggleRecording() {
        taps.withLock { if (recorder.recordingState.value.isLive()) stop() else start() }
    }

    /** Reports each recording's end until cancelled; an end from before the call is not reported. */
    suspend fun reportEnds() {
        var reported = (recorder.recordingState.value as? RecordingState.Finished)?.recordingId
        recorder.recordingState.filterIsInstance<RecordingState.Finished>().collect { finished ->
            if (finished.recordingId != reported) {
                reported = finished.recordingId
                report(finished)
            }
        }
    }

    private suspend fun start() {
        when (val started = recorder.startRecording(configuration)) {
            is CameraKResult.Success -> println("Recording started")
            is CameraKResult.Failure -> {
                val error = started.error
                CameraKLogger.w(TAG, "Starting a recording failed: ${error::class.simpleName}", error.cause)
                _notices.trySend("Recording did not start: ${error.recordingText()}")
            }
        }
    }

    // The holder finalizes the file even when the tap's coroutine goes away; the end is reported by
    // reportEnds, from the recording state.
    private suspend fun stop() {
        val stopped = withContext(NonCancellable) { recorder.stopRecording() }
        if (stopped is CameraKResult.Failure && stopped.error is CameraKError.InvalidState) {
            CameraKLogger.w(TAG, "Stopped a recording that had already ended")
        }
    }

    private fun report(finished: RecordingState.Finished) {
        val notice = when (val result = finished.result) {
            is CameraKResult.Success -> {
                println("Recording saved: ${result.value.path}")
                when (finished.reason) {
                    RecordingEndReason.MAX_DURATION -> "Recording reached its time limit and was saved"
                    RecordingEndReason.MAX_FILE_SIZE -> "Recording reached its size limit and was saved"
                    else -> "Recording saved"
                }
            }
            is CameraKResult.Failure -> {
                CameraKLogger.e(TAG, "The recording failed: ${result.error::class.simpleName}", result.error.cause)
                "Recording failed: ${result.error.recordingText()}"
            }
        }
        _notices.trySend(notice)
    }

    private companion object {
        const val TAG = "Sample"
    }
}

private fun CameraKError.recordingText(): String = when (this) {
    is CameraKError.Permission -> "microphone access is needed"
    is CameraKError.Storage -> "could not write to storage"
    is CameraKError.Timeout -> "the camera did not answer in time"
    is CameraKError.Busy -> "a recording is already under way"
    is CameraKError.Unsupported -> "this camera cannot record at that quality"
    is CameraKError.Disconnected -> "the camera was disconnected"
    CameraKError.NotReady -> "the camera is not ready"
    CameraKError.Closed -> "the camera is closed"
    else -> "the camera reported an error"
}

/** The Sample's recording on [holder], remembered while the holder lives. */
@Composable
internal fun rememberRecordingFeature(holder: CameraKStateHolder): RecordingFeature {
    val recording = remember(holder) { RecordingFeature(holder.asSampleRecorder()) }
    LaunchedEffect(recording) { recording.reportEnds() }
    return recording
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

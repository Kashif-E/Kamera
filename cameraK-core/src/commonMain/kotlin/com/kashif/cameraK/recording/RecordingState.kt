package com.kashif.cameraK.recording

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.video.VideoConfiguration
import dev.drewhamilton.poko.Poko
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration

/**
 * Closed hierarchy: where a recording is. The holder owns the one recording state; its subtypes
 * compare by value.
 */
public sealed interface RecordingState {
    public data object Idle : RecordingState

    @Poko
    public class Starting(public val config: VideoConfiguration) : RecordingState

    /**
     * @property recordingId Increases per holder; the same id is on [Finished] and on the event that
     *   reports an end the app did not ask for.
     * @property activeSince When recording last started or resumed, from the holder's time source.
     * @property accumulated Recorded time before [activeSince].
     */
    @Poko
    public class Recording(
        public val recordingId: Long,
        public val filePath: String,
        public val activeSince: ComparableTimeMark,
        public val accumulated: Duration,
        public val hasAudio: Boolean,
    ) : RecordingState

    @Poko
    public class Paused(public val recordingId: Long, public val filePath: String, public val accumulated: Duration) :
        RecordingState

    @Poko
    public class Stopping(
        public val recordingId: Long,
        public val filePath: String,
        public val reason: RecordingEndReason,
    ) : RecordingState

    /** Resting state after any end, for display. startRecording is legal from here as from [Idle]. */
    @Poko
    public class Finished(
        public val recordingId: Long,
        public val result: CameraKResult<RecordedVideo>,
        public val reason: RecordingEndReason,
    ) : RecordingState
}

/** Recorded time so far: live while [RecordingState.Recording], frozen while paused, zero otherwise. */
public fun RecordingState.elapsed(): Duration = when (this) {
    is RecordingState.Recording -> accumulated + activeSince.elapsedNow()
    is RecordingState.Paused -> accumulated
    else -> Duration.ZERO
}

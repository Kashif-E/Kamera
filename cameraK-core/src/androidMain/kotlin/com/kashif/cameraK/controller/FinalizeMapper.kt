package com.kashif.cameraK.controller

import androidx.camera.video.VideoRecordEvent
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult

/** How the engine reports a recording CameraX finalized: its outcome and why it ended. */
internal class MappedFinalize(val result: CameraKResult<RecordedVideo>, val reason: RecordingEndReason)

/** What a recording's Finalize means for the caller. */
internal sealed interface FinalizeOutcome {
    /** The file holds the recording. [endedBy] is the CameraX error that ended it early, or null. */
    data class Recorded(val durationMs: Long, val endedBy: Int?) : FinalizeOutcome

    /** The recording failed with CameraX error [error] and its file is not kept. */
    data class Failed(val error: Int) : FinalizeOutcome
}

/**
 * Maps CameraX's Finalize to a [FinalizeOutcome]. Several errors end the recording but leave a
 * playable file (the camera was taken away, a size or duration limit or a full disk stopped it);
 * those keep the file when it is playable.
 */
internal object FinalizeMapper {
    // CameraX stops at either configured limit and finalizes a playable file.
    private val PLAYABLE_FINALIZE_ERRORS = setOf(
        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
        VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
        VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
        VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE,
    )

    fun map(event: RecordingFinalized, fileIsPlayable: Boolean): FinalizeOutcome {
        val keepsFile = !event.hasError || (event.error in PLAYABLE_FINALIZE_ERRORS && fileIsPlayable)
        if (!keepsFile) return FinalizeOutcome.Failed(event.error)
        return FinalizeOutcome.Recorded(
            durationMs = event.recordedDurationNanos / 1_000_000,
            endedBy = event.error.takeIf { event.hasError },
        )
    }

    /** Every `VideoRecordEvent.Finalize.ERROR_*` code the mapper has a row for (A3'). */
    val handledCodes: Set<Int> get() = TODO("A3': the exhaustive Finalize table")

    /** The engine's outcome for [event] over [video], as §4.1's Android table maps it (A3'). */
    fun mapRecording(
        event: RecordingFinalized,
        playable: Boolean,
        video: RecordedVideo,
        requested: RecordingEndReason,
    ): MappedFinalize = TODO("A3': the exhaustive Finalize table")
}

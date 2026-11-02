package com.kashif.cameraK.controller

/** What a recording's Finalize means for the caller. */
internal sealed interface FinalizeOutcome {
    /** The file holds the recording. [endedBy] is the CameraX error that ended it early, or null. */
    data class Recorded(val durationMs: Long, val endedBy: Int?) : FinalizeOutcome

    /** The recording failed with CameraX error [error] and its file is not kept. */
    data class Failed(val error: Int) : FinalizeOutcome
}

/** Maps CameraX's Finalize to a [FinalizeOutcome]. */
internal object FinalizeMapper {
    fun map(event: RecordingFinalized, fileIsPlayable: Boolean): FinalizeOutcome = TODO("A0: finalize mapping")
}

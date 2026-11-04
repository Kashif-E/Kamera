package com.kashif.cameraK.controller

import androidx.camera.video.VideoRecordEvent.Finalize
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.StorageFailure
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi

/** How the engine reports a recording CameraX finalized: its outcome and why it ended. */
internal class MappedFinalize(val result: CameraKResult<RecordedVideo>, val reason: RecordingEndReason)

/**
 * Maps CameraX's Finalize to the engine's outcome, as design §4.1's Android table does, under §5's
 * partial-file rule: a recording that ends on a platform error is a Success when its file is
 * [playable] and the row's Failure when it is not; a full disk is `Storage(FULL)` with the kept file;
 * codes that by definition leave no file are always a Failure. The Finalize cause is every Failure's
 * cause. A code newer than this table (CameraX added one) maps as ERROR_UNKNOWN does.
 */
@OptIn(CameraKEngineApi::class)
internal object FinalizeMapper {
    /** Every `VideoRecordEvent.Finalize.ERROR_*` code the table has a row for. */
    val handledCodes: Set<Int> = setOf(
        Finalize.ERROR_NONE,
        Finalize.ERROR_UNKNOWN,
        Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
        Finalize.ERROR_INSUFFICIENT_STORAGE,
        Finalize.ERROR_SOURCE_INACTIVE,
        Finalize.ERROR_INVALID_OUTPUT_OPTIONS,
        Finalize.ERROR_ENCODING_FAILED,
        Finalize.ERROR_RECORDER_ERROR,
        Finalize.ERROR_NO_VALID_DATA,
        Finalize.ERROR_DURATION_LIMIT_REACHED,
        Finalize.ERROR_RECORDING_GARBAGE_COLLECTED,
    )

    /** Codes that mean a CameraK bug: CameraX knows nothing better, or the recording was leaked. */
    val bugCodes: Set<Int> = setOf(Finalize.ERROR_UNKNOWN, Finalize.ERROR_RECORDING_GARBAGE_COLLECTED)

    /**
     * The outcome of [event] over [video], the file it recorded into. [requested] is why the engine
     * stopped it, USER or SHUTDOWN, which is the reason of a recording that ended without an error.
     */
    fun mapRecording(
        event: RecordingFinalized,
        playable: Boolean,
        video: RecordedVideo,
        requested: RecordingEndReason,
    ): MappedFinalize {
        val code = event.error
        val cause = event.cause
        fun ended(reason: RecordingEndReason, otherwise: () -> CameraKError) =
            if (playable) MappedFinalize(CameraKResult.Success(video), reason) else failed(otherwise(), reason)
        val hardware = { CameraKError.Hardware(code, cause) }
        val internal = { CameraKError.Internal(cause) }
        return when (code) {
            Finalize.ERROR_NONE -> ended(requested) {
                CameraKError.Internal(
                    cause ?: IllegalStateException("CameraX finalized a recording that is not playable"),
                )
            }
            Finalize.ERROR_DURATION_LIMIT_REACHED -> ended(RecordingEndReason.MAX_DURATION, hardware)
            Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> ended(RecordingEndReason.MAX_FILE_SIZE, hardware)
            Finalize.ERROR_SOURCE_INACTIVE -> ended(RecordingEndReason.INTERRUPTED, hardware)
            Finalize.ERROR_INSUFFICIENT_STORAGE -> failed(
                CameraKError.Storage(StorageFailure.FULL, keptFilePath = video.path.takeIf { playable }, cause = cause),
                RecordingEndReason.ERROR,
            )
            Finalize.ERROR_ENCODING_FAILED, Finalize.ERROR_RECORDER_ERROR -> ended(RecordingEndReason.ERROR, hardware)
            Finalize.ERROR_NO_VALID_DATA -> failed(internal(), RecordingEndReason.ERROR)
            Finalize.ERROR_INVALID_OUTPUT_OPTIONS -> failed(
                CameraKError.Storage(StorageFailure.NOT_WRITABLE, cause = cause),
                RecordingEndReason.ERROR,
            )
            else -> ended(RecordingEndReason.ERROR, internal)
        }
    }

    private fun failed(error: CameraKError, reason: RecordingEndReason) =
        MappedFinalize(CameraKResult.Failure(error), reason)
}

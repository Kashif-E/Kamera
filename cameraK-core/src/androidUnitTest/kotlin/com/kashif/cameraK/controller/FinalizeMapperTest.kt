package com.kashif.cameraK.controller

import androidx.camera.video.VideoRecordEvent.Finalize
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Every CameraX Finalize code ends the recording. A normal stop keeps the file; the errors that
 * stop a recording early but leave a playable file (the camera taken away, a size or duration limit,
 * a full disk) keep it only when it is playable; every other error fails the recording.
 */
class FinalizeMapperTest {
    @Test
    fun mapsEachHandledCode() {
        val keptWhenPlayable = listOf(
            Finalize.ERROR_SOURCE_INACTIVE,
            Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
            Finalize.ERROR_DURATION_LIMIT_REACHED,
            Finalize.ERROR_INSUFFICIENT_STORAGE,
        )
        val failed = listOf(
            Finalize.ERROR_UNKNOWN,
            Finalize.ERROR_INVALID_OUTPUT_OPTIONS,
            Finalize.ERROR_ENCODING_FAILED,
            Finalize.ERROR_RECORDER_ERROR,
            Finalize.ERROR_NO_VALID_DATA,
            Finalize.ERROR_RECORDING_GARBAGE_COLLECTED,
        )

        for (playable in listOf(true, false)) {
            assertEquals(
                FinalizeOutcome.Recorded(durationMs = 2_000L, endedBy = null),
                map(Finalize.ERROR_NONE, playable),
                "a normal stop, playable = $playable",
            )
        }
        for (code in keptWhenPlayable) {
            assertEquals(FinalizeOutcome.Recorded(durationMs = 2_000L, endedBy = code), map(code, true), "code $code")
            assertEquals(FinalizeOutcome.Failed(code), map(code, false), "code $code without a playable file")
        }
        for (code in failed) {
            assertEquals(FinalizeOutcome.Failed(code), map(code, true), "code $code with a playable file")
            assertEquals(FinalizeOutcome.Failed(code), map(code, false), "code $code")
        }
    }

    private fun map(code: Int, playable: Boolean) =
        FinalizeMapper.map(RecordingFinalized(code, null, recordedDurationNanos = 2_000_000_000L), playable)
}

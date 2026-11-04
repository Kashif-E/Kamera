@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import androidx.camera.video.VideoRecordEvent.Finalize
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.StorageFailure
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import org.junit.Test
import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

/**
 * Every CameraX Finalize code ends the recording. A normal stop keeps the file; the errors that
 * stop a recording early but leave a playable file (the camera taken away, a size or duration limit,
 * a full disk) keep it only when it is playable; every other error fails the recording.
 *
 * From A3' the mapper gives the engine's outcome (§4.1's Android table): it is exhaustive over
 * CameraX's Finalize codes, every recording row follows the playable probe, and the Finalize cause
 * is kept.
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

    @Test
    fun everyFinalizeErrorCodeIsMapped() {
        val codes = Finalize::class.java.fields
            .filter {
                Modifier.isStatic(it.modifiers) &&
                    it.type == Int::class.javaPrimitiveType &&
                    it.name.startsWith("ERROR_")
            }
            .associate { it.getInt(null) to it.name }

        assertEquals(
            codes.keys,
            FinalizeMapper.handledCodes,
            "FinalizeMapper has no row for ${codes.filterKeys { it !in FinalizeMapper.handledCodes }.values}",
        )
    }

    @Test
    fun recordingRowsFollowPlayable() {
        for (playable in listOf(true, false)) {
            fun success(reason: RecordingEndReason) = Expected(null, null, reason)
            fun failure(error: KClass<out CameraKError>, reason: RecordingEndReason, keptPath: String? = null) =
                Expected(error, keptPath, reason)
            val rows = mapOf(
                Finalize.ERROR_NONE to if (playable) success(USER) else failure(CameraKError.Internal::class, USER),
                Finalize.ERROR_DURATION_LIMIT_REACHED to
                    if (playable) success(MAX_DURATION) else failure(CameraKError.Hardware::class, MAX_DURATION),
                Finalize.ERROR_FILE_SIZE_LIMIT_REACHED to
                    if (playable) success(MAX_FILE_SIZE) else failure(CameraKError.Hardware::class, MAX_FILE_SIZE),
                Finalize.ERROR_SOURCE_INACTIVE to
                    if (playable) success(INTERRUPTED) else failure(CameraKError.Hardware::class, INTERRUPTED),
                Finalize.ERROR_INSUFFICIENT_STORAGE to
                    failure(CameraKError.Storage::class, ERROR, keptPath = if (playable) PATH else null),
                Finalize.ERROR_ENCODING_FAILED to
                    if (playable) success(ERROR) else failure(CameraKError.Hardware::class, ERROR),
                Finalize.ERROR_RECORDER_ERROR to
                    if (playable) success(ERROR) else failure(CameraKError.Hardware::class, ERROR),
                Finalize.ERROR_NO_VALID_DATA to failure(CameraKError.Internal::class, ERROR),
                Finalize.ERROR_INVALID_OUTPUT_OPTIONS to failure(CameraKError.Storage::class, ERROR),
                Finalize.ERROR_UNKNOWN to if (playable) {
                    success(
                        ERROR,
                    )
                } else {
                    failure(CameraKError.Internal::class, ERROR)
                },
                Finalize.ERROR_RECORDING_GARBAGE_COLLECTED to
                    if (playable) success(ERROR) else failure(CameraKError.Internal::class, ERROR),
            )
            for ((code, expected) in rows) {
                val mapped = FinalizeMapper.mapRecording(finalized(code), playable, VIDEO, requested = USER)
                val what = "code $code, playable = $playable"
                assertEquals(expected.reason, mapped.reason, what)
                if (expected.error == null) {
                    assertEquals(CameraKResult.Success(VIDEO), mapped.result, what)
                } else {
                    val error = assertIs<CameraKResult.Failure>(mapped.result, what).error
                    assertEquals(expected.error, error::class, what)
                    if (error is CameraKError.Hardware) assertEquals(code, error.platformCode, what)
                    if (error is CameraKError.Storage) {
                        val kind = if (code ==
                            Finalize.ERROR_INSUFFICIENT_STORAGE
                        ) {
                            StorageFailure.FULL
                        } else {
                            StorageFailure.NOT_WRITABLE
                        }
                        assertEquals(kind, error.kind, what)
                        assertEquals(expected.keptPath, error.keptFilePath, what)
                    }
                }
            }
        }
        // A stop the holder asked for as part of shutting down ends with that reason.
        assertEquals(
            SHUTDOWN,
            FinalizeMapper.mapRecording(finalized(Finalize.ERROR_NONE), true, VIDEO, SHUTDOWN).reason,
        )
    }

    @Test
    fun finalizeCauseIsKept() {
        val cause = IllegalStateException("scripted encoder failure")
        for (code in listOf(
            Finalize.ERROR_ENCODING_FAILED,
            Finalize.ERROR_SOURCE_INACTIVE,
            Finalize.ERROR_INSUFFICIENT_STORAGE,
        )) {
            val mapped = FinalizeMapper.mapRecording(finalized(code, cause), playable = false, VIDEO, requested = USER)
            assertSame(cause, assertIs<CameraKResult.Failure>(mapped.result).error.cause, "code $code lost its cause")
        }
    }

    private class Expected(
        val error: KClass<out CameraKError>?,
        val keptPath: String?,
        val reason: RecordingEndReason = USER,
    )

    private fun finalized(code: Int, cause: Throwable? = null) =
        RecordingFinalized(code, cause, recordedDurationNanos = 2_000_000_000L)

    private fun map(code: Int, playable: Boolean) =
        FinalizeMapper.map(RecordingFinalized(code, null, recordedDurationNanos = 2_000_000_000L), playable)

    private companion object {
        const val PATH = "/data/CameraK/VID_1.mp4"
        val VIDEO = RecordedVideo(PATH, 2.seconds, 1920, 1080, bitrateBps = null, hasAudio = false)
        val USER = RecordingEndReason.USER
        val SHUTDOWN = RecordingEndReason.SHUTDOWN
        val MAX_DURATION = RecordingEndReason.MAX_DURATION
        val MAX_FILE_SIZE = RecordingEndReason.MAX_FILE_SIZE
        val INTERRUPTED = RecordingEndReason.INTERRUPTED
        val ERROR = RecordingEndReason.ERROR
    }
}

package com.kashif.cameraK.controller

import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.StorageFailure
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.utils.CameraKLogger
import platform.AVFoundation.AVErrorDiskFull
import platform.AVFoundation.AVErrorMaximumDurationReached
import platform.AVFoundation.AVErrorMaximumFileSizeReached
import platform.AVFoundation.AVErrorMediaServicesWereReset
import platform.AVFoundation.AVErrorSessionConfigurationChanged
import platform.AVFoundation.AVErrorSessionWasInterrupted
import platform.AVFoundation.AVErrorUnknown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Design §4.1, iOS, a recording's end. Each row is conditional on AVFoundation's playable verdict,
 * AVErrorRecordingSuccessfullyFinishedKey (or no error at all): a playable file is a success whose
 * reason says why it ended, an unplayable one a failure; a full disk is a Storage(FULL) failure that
 * keeps the playable file's path. Tested over both values of the key.
 */
class MovieErrorMapperTest {
    private val path = "/recordings/VID_1.mp4"

    @Test
    fun sessionWasInterruptedIsInterrupted() {
        for (code in listOf(AVErrorSessionWasInterrupted, AVErrorMediaServicesWereReset)) {
            val playable = movieEnd(movieError(code, successfullyFinished = true), requested = null, path)
            assertEquals(RecordingEndReason.INTERRUPTED, playable.reason, "code $code, playable")
            assertNull(playable.error, "a playable interrupted recording is a success")

            val error = movieError(code, successfullyFinished = false)
            val unplayable = movieEnd(error, requested = null, path)
            assertEquals(RecordingEndReason.INTERRUPTED, unplayable.reason, "code $code, unplayable")
            val hardware = assertIs<CameraKError.Hardware>(unplayable.error)
            assertEquals(code.toInt(), hardware.platformCode)
            assertSame(error, hardware.cause?.platformError())
        }
    }

    @Test
    fun configurationChangedIsInterruptedAndLoggedAsBug() {
        capturingLogEntries { logs ->
            val end = movieEnd(movieError(AVErrorSessionConfigurationChanged, successfullyFinished = true), null, path)

            assertEquals(RecordingEndReason.INTERRUPTED, end.reason)
            assertNull(end.error)
            assertTrue(
                logs().any { it.level == CameraKLogger.Level.ERROR && "CameraK bug" in it.message },
                "a configuration change during a recording was not logged as a CameraK bug: ${logs()}",
            )
        }
    }

    @Test
    fun otherErrorWithSuccessKeyIsSuccessError() {
        val end = movieEnd(movieError(AVErrorUnknown, successfullyFinished = true), requested = null, path)

        assertEquals(RecordingEndReason.ERROR, end.reason)
        assertNull(end.error, "a playable file is a success")
    }

    @Test
    fun otherErrorWithoutSuccessKeyIsFailureHardware() {
        val error = movieError(AVErrorUnknown, successfullyFinished = false)

        val end = movieEnd(error, requested = null, path)

        assertEquals(RecordingEndReason.ERROR, end.reason)
        val hardware = assertIs<CameraKError.Hardware>(end.error)
        assertEquals(AVErrorUnknown.toInt(), hardware.platformCode)
        assertSame(error, hardware.cause?.platformError(), "the NSError is the cause")
    }

    @Test
    fun theLimitsAreSuccessesWithTheirReasons() {
        assertEquals(
            RecordingEndReason.MAX_DURATION,
            movieEnd(movieError(AVErrorMaximumDurationReached, true), null, path).reason,
        )
        assertEquals(
            RecordingEndReason.MAX_FILE_SIZE,
            movieEnd(movieError(AVErrorMaximumFileSizeReached, true), null, path).reason,
        )
        assertNull(movieEnd(movieError(AVErrorMaximumFileSizeReached, true), null, path).error)
    }

    @Test
    fun aFullDiskKeepsThePlayableFilesPath() {
        val playable = movieEnd(movieError(AVErrorDiskFull, successfullyFinished = true), null, path)
        val kept = assertIs<CameraKError.Storage>(playable.error)
        assertEquals(StorageFailure.FULL, kept.kind)
        assertEquals(path, kept.keptFilePath)
        assertEquals(RecordingEndReason.ERROR, playable.reason)

        val unplayable = movieEnd(movieError(AVErrorDiskFull, successfullyFinished = false), null, path)
        assertNull(assertIs<CameraKError.Storage>(unplayable.error).keptFilePath, "an unplayable file is not kept")
    }

    @Test
    fun aRequestedEndWithoutAnErrorHasTheRequestedReason() {
        for (requested in listOf(RecordingEndReason.USER, RecordingEndReason.SHUTDOWN)) {
            val end = movieEnd(error = null, requested = requested, path)
            assertEquals(requested, end.reason)
            assertNull(end.error)
        }
    }
}

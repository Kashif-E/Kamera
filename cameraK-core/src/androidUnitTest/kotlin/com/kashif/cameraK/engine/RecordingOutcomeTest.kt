@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import androidx.camera.video.VideoRecordEvent.Finalize
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.StorageFailure
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * §5's partial-file rule over CameraX's Finalize: a playable file is a Success whatever ended it, with
 * the reason saying why; a full disk is `Storage(FULL)` with the kept file; an unplayable file is a
 * Failure and is deleted. "Playable" is the probe of §4.1: MediaMetadataRetriever reads a duration.
 */
@RunWith(RobolectricTestRunner::class)
class RecordingOutcomeTest {
    private val platform = FakeAndroidPlatform()

    @Test
    fun sourceInactiveWithDataIsSuccessInterrupted() = runTest {
        val (finalized, path) = platformEnd(Finalize.ERROR_SOURCE_INACTIVE, playable = true)

        assertEquals(RecordingEndReason.INTERRUPTED, finalized.reason)
        assertEquals(path, assertIs<CameraKResult.Success<RecordedVideo>>(finalized.result).value.path)
    }

    @Test
    fun insufficientStorageKeepsPlayableFile() = runTest {
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        val events = recordingEventsOf(engine)
        val (recording, path) = startRecording(platform, engine)

        val stop = async { engine.stopRecording() }
        platform.settle(testScheduler)
        recording.finalize(Finalize.ERROR_INSUFFICIENT_STORAGE)
        platform.settle(testScheduler)

        val error = assertIs<CameraKError.Storage>(assertIs<CameraKResult.Failure>(stop.await()).error)
        assertEquals(StorageFailure.FULL, error.kind)
        assertEquals(path, error.keptFilePath, "the playable file was not kept")
        assertTrue(recording.outputFile.exists(), "the kept file was deleted")
        val finalized = assertIs<EngineRecordingEvent.Finalized>(events.last())
        assertEquals(RecordingEndReason.ERROR, finalized.reason)
        engine.closeAndJoin()
    }

    @Test
    fun unplayableFileIsDeleted() = runTest {
        val (finalized, _) = platformEnd(Finalize.ERROR_SOURCE_INACTIVE, playable = false)

        val error = assertIs<CameraKError.Hardware>(assertIs<CameraKResult.Failure>(finalized.result).error)
        assertEquals(Finalize.ERROR_SOURCE_INACTIVE, error.platformCode)
        assertFalse(platform.backend.recordings.single().outputFile.exists(), "the unplayable file was kept")
    }

    @Test
    fun encodingFailedWithPlayableFileIsSuccessError() = runTest {
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        val events = recordingEventsOf(engine)
        val (recording, path) = startRecording(platform, engine)

        val stop = async { engine.stopRecording() }
        platform.settle(testScheduler)
        recording.finalize(Finalize.ERROR_ENCODING_FAILED)
        platform.settle(testScheduler)

        assertEquals(path, assertIs<CameraKResult.Success<RecordedVideo>>(stop.await()).value.path)
        assertEquals(RecordingEndReason.ERROR, assertIs<EngineRecordingEvent.Finalized>(events.last()).reason)
        assertTrue(recording.outputFile.exists())
        engine.closeAndJoin()
    }

    @Test
    fun encodingFailedWithoutPlayableFileIsFailureHardwareAndDeleted() = runTest {
        val (finalized, _) = platformEnd(Finalize.ERROR_ENCODING_FAILED, playable = false)

        assertEquals(RecordingEndReason.ERROR, finalized.reason)
        val error = assertIs<CameraKError.Hardware>(assertIs<CameraKResult.Failure>(finalized.result).error)
        assertEquals(Finalize.ERROR_ENCODING_FAILED, error.platformCode)
        assertFalse(platform.backend.recordings.single().outputFile.exists(), "the unplayable file was kept")
    }

    // CameraX ends the recording with [error], over a file that is or is not playable.
    private suspend fun kotlinx.coroutines.test.TestScope.platformEnd(
        error: Int,
        playable: Boolean,
    ): Pair<EngineRecordingEvent.Finalized, String> {
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        val events = recordingEventsOf(engine)
        val (recording, path) = startRecording(platform, engine)

        recording.finalize(error, playable = playable)
        platform.settle(testScheduler)

        val finalized = assertIs<EngineRecordingEvent.Finalized>(events.lastOrNull(), "events: $events")
        engine.closeAndJoin()
        return finalized to path
    }
}

@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.controller

import androidx.camera.video.VideoRecordEvent
import dev.kashifmehmood.camerak.capture.RecordedVideo
import dev.kashifmehmood.camerak.engine.EngineRecordingEvent
import dev.kashifmehmood.camerak.engine.FakeAndroidPlatform
import dev.kashifmehmood.camerak.engine.open
import dev.kashifmehmood.camerak.engine.recordingEventsOf
import dev.kashifmehmood.camerak.engine.startRecording
import dev.kashifmehmood.camerak.recording.RecordingEndReason
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import dev.kashifmehmood.camerak.video.VideoConfiguration
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A long recording at high resolution can fill the device. The size and duration limits in the
 * configuration go to CameraX's Recorder, which stops at the limit and finalizes a playable file.
 */
@RunWith(RobolectricTestRunner::class)
class RecordingLimitsTest {
    private val platform = FakeAndroidPlatform()
    private val backend = platform.backend

    @Test
    fun sizeAndDurationLimits_reachTheRecorder() = runTest {
        start(
            VideoConfiguration {
                enableAudio = false
                maxDuration = 30.seconds
                maxFileSizeBytes = 50_000_000L
            },
        )

        val output = backend.recordings.single().output
        assertEquals(30_000L, output.durationLimitMillis, "the duration limit never reached CameraX")
        assertEquals(50_000_000L, output.fileSizeLimit, "the file size limit never reached CameraX")
    }

    @Test
    fun noLimits_recordWithoutLimits() = runTest {
        start(VideoConfiguration { enableAudio = false })

        val output = backend.recordings.single().output
        assertEquals(0L, output.durationLimitMillis)
        assertEquals(0L, output.fileSizeLimit)
    }

    @Test
    fun subMillisecondDurationLimit_isOneMillisecond() = runTest {
        start(
            VideoConfiguration {
                enableAudio = false
                maxDuration = 500.microseconds
            },
        )

        // CameraX reads 0 as no limit, so a positive limit under a millisecond must not round down to it.
        assertEquals(1L, backend.recordings.single().output.durationLimitMillis, "the limit became unlimited")
    }

    @Test
    fun recordingStoppedAtItsSizeLimit_keepsItsFile() = runTest {
        val events = start(
            VideoConfiguration {
                enableAudio = false
                maxFileSizeBytes = 4L
            },
        )
        backend.recordings.single().finalize(error = VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED)
        platform.settle(testScheduler)

        val finalized = events.filterIsInstance<EngineRecordingEvent.Finalized>().single()
        assertEquals(RecordingEndReason.MAX_FILE_SIZE, finalized.reason)
        val kept = assertIs<CameraKResult.Success<RecordedVideo>>(finalized.result).value
        assertEquals(backend.recordings.single().outputFile.absolutePath, kept.path)
    }

    private suspend fun TestScope.start(configuration: VideoConfiguration): List<EngineRecordingEvent> {
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        val events = recordingEventsOf(engine)
        startRecording(platform, engine, configuration)
        return events
    }
}

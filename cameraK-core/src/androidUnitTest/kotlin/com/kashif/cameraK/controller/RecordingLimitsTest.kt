@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import androidx.camera.video.VideoRecordEvent
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.EngineRecordingEvent
import com.kashif.cameraK.engine.FakeAndroidPlatform
import com.kashif.cameraK.engine.open
import com.kashif.cameraK.engine.recordingEventsOf
import com.kashif.cameraK.engine.startRecording
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
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

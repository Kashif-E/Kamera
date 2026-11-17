@file:OptIn(CameraKEngineApi::class, ExperimentalForeignApi::class)

package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.engine.contract.AppleEngineDriver
import dev.kashifmehmood.camerak.recording.RecordingEndReason
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import dev.kashifmehmood.camerak.video.VideoConfiguration
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVErrorMaximumFileSizeReached
import platform.CoreMedia.CMTimeGetSeconds
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * The limits go to the movie output (design §5): maxDuration to maxRecordedDuration and
 * maxFileSizeBytes to maxRecordedFileSize (recording-no-size-limit), and AVFoundation's end at either
 * comes back as the recording's one Finalized with MAX_DURATION or MAX_FILE_SIZE.
 */
class MovieLimitsTest {
    private val driver = AppleEngineDriver()
    private val written = mutableListOf<String>()

    @AfterTest
    fun tearDown() = removeFiles(written)

    @Test
    fun maxFileSizeSetsMaxRecordedFileSize() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        val events = recordingEventsOf(engine)
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

        val limited = startEngineRecording(
            driver,
            engine,
            VideoConfiguration {
                maxFileSizeBytes = 10_000_000L
                maxDuration = 30.seconds
            },
        ).also { written += it.path }

        assertEquals(10_000_000L, limited.start.output.maxRecordedFileSize, "the movie output's size limit")
        assertEquals(
            30.0,
            CMTimeGetSeconds(limited.start.output.maxRecordedDuration),
            "the movie output's duration limit",
        )
        finishRecording(driver, limited, movieError(AVErrorMaximumFileSizeReached, successfullyFinished = true))
        assertEquals(RecordingEndReason.MAX_FILE_SIZE, events.finalized().single().reason)

        val unlimited = startEngineRecording(driver, engine, VideoConfiguration()).also { written += it.path }
        assertEquals(0L, unlimited.start.output.maxRecordedFileSize, "no size limit is AVFoundation's 0")
        engine.close()
    }
}

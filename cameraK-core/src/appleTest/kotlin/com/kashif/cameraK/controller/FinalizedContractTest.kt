@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.async
import platform.AVFoundation.AVErrorMaximumDurationReached
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The Finalized contract (w6 decisions; the kit case is C1''s), pinned on the iOS engine over its fake
 * movie output: every recording the engine started yields exactly one Finalized, whether the app
 * stopped it or AVFoundation ended it, in start order, possibly after stopRecording returned; and
 * stopRecording on a recording the engine never began returns Failure(InvalidState(RECORD_STOP)) with
 * no Finalized after it.
 */
class FinalizedContractTest {
    private val driver = AppleEngineDriver()
    private val written = mutableListOf<String>()

    @AfterTest
    fun tearDown() = removeFiles(written)

    @Test
    fun everyStartedRecordingYieldsExactlyOneFinalizedInStartOrder() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        val events = recordingEventsOf(engine)
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

        val first = startEngineRecording(driver, engine).also { written += it.path }
        val stop = async { engine.stopRecording() }
        driver.settle()
        finishRecording(driver, first)
        repeat(20) { if (!stop.isCompleted) driver.settle() }
        assertEquals(first.path, assertIs<CameraKResult.Success<RecordedVideo>>(stop.await()).value.path)

        val second = startEngineRecording(driver, engine).also { written += it.path }
        finishRecording(driver, second, movieError(AVErrorMaximumDurationReached, successfullyFinished = true))
        driver.settle()

        val finalized = events.finalized()
        assertEquals(2, finalized.size, "Finalized events for two recordings: ${events.values}")
        assertEquals(
            listOf(first.path to RecordingEndReason.USER, second.path to RecordingEndReason.MAX_DURATION),
            finalized.map { assertIs<CameraKResult.Success<RecordedVideo>>(it.result).value.path to it.reason },
            "each recording's one Finalized, in start order",
        )
        engine.close()
    }

    @Test
    fun stopRecordingOnARecordingNeverBegunIsInvalidStateAndNoFinalizedFollows() {
        val noUsageDescription = AppleEngineDriver(hasUsageDescription = { false })
        noUsageDescription.runCase {
            val engine = noUsageDescription.createEngine()
            noUsageDescription.settle()
            val events = recordingEventsOf(engine)
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

            for (attempt in listOf("with no start", "after a start that reached nothing")) {
                val stopped = engine.stopRecording()
                val invalid =
                    assertIs<CameraKError.InvalidState>(assertIs<CameraKResult.Failure>(stopped).error, attempt)
                assertEquals(CameraOperation.RECORD_STOP, invalid.operation, attempt)
                assertIs<CameraKResult.Failure>(engine.startRecording(VideoConfiguration(enableAudio = true)))
            }
            noUsageDescription.settle()
            assertEquals(emptyList(), events.finalized(), "a Finalized for a recording the engine never began")
            assertEquals(0, noUsageDescription.movieRecorder.starts.size, "the movie output was started")
            engine.close()
        }
    }
}

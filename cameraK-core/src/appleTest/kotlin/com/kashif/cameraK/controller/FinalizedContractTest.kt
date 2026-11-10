@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import platform.AVFoundation.AVErrorMaximumDurationReached
import platform.AVFoundation.AVErrorUnknown
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        driver.settleUntil("the stop to complete") { stop.isCompleted }
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
                assertIs<CameraKResult.Failure>(engine.startRecording(VideoConfiguration { enableAudio = true }))
            }
            noUsageDescription.settle()
            assertEquals(emptyList(), events.finalized(), "a Finalized for a recording the engine never began")
            assertEquals(0, noUsageDescription.movieRecorder.starts.size, "the movie output was started")
            engine.close()
        }
    }

    /**
     * The holder gives up on a start AVFoundation has not confirmed (its timeout is shorter than the
     * engine's) and calls stopRecording; AVFoundation then finishes the file without ever reporting
     * it started. The stop reached a movie output the start had begun, so it cannot answer
     * InvalidState(RECORD_STOP), and the recording yields its one Finalized like any other: the holder
     * counts on that Finalized, and would otherwise take the next recording's as this one's.
     */
    @Test
    fun aStopAfterAGivenUpStartThatAVFoundationFinishesUnstartedYieldsOneFinalized() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        val events = recordingEventsOf(engine)
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        val (start, begun) = startReachingTheMovieOutput(engine)
        start.cancelAndJoin()

        val stop = async { engine.stopRecording() }
        repeat(20) { if (driver.movieRecorder.stops == 0) driver.settle() }
        driver.movieRecorder.reportFinished(begun, movieError(AVErrorUnknown, successfullyFinished = false))
        driver.settleUntil("the stop to complete") { stop.isCompleted }
        val stopped = stop.await()
        driver.settle()

        val error = assertIs<CameraKResult.Failure>(stopped, "the stop of a recording AVFoundation failed").error
        assertFalse(
            error is CameraKError.InvalidState && error.operation == CameraOperation.RECORD_STOP,
            "the stop said nothing reached the platform, but the movie output had been started",
        )
        val finalized = events.finalized()
        assertEquals(1, finalized.size, "Finalized events after the given-up start's stop: ${events.values}")
        assertEquals(stopped, finalized.single().result, "the Finalized carries the stop's outcome")
        engine.close()
    }

    /**
     * Characterization: a start given up on, which AVFoundation then ends by itself before reporting
     * it started, leaves nothing to stop. The stop answers InvalidState(RECORD_STOP) and no Finalized
     * follows, so the holder owes none.
     */
    @Test
    fun aGivenUpStartAVFoundationEndsBeforeAnyStopLeavesNothingToStopAndNoFinalized() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        val events = recordingEventsOf(engine)
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        val (start, begun) = startReachingTheMovieOutput(engine)
        start.cancelAndJoin()

        driver.movieRecorder.reportFinished(begun, movieError(AVErrorUnknown, successfullyFinished = false))
        driver.settle()
        val stopped = engine.stopRecording()
        driver.settle()

        val invalid = assertIs<CameraKError.InvalidState>(assertIs<CameraKResult.Failure>(stopped).error)
        assertEquals(CameraOperation.RECORD_STOP, invalid.operation)
        assertEquals(emptyList(), events.finalized(), "a Finalized for a start that started nothing")
        engine.close()
    }

    /** A startRecording whose movie output has started, with no didStartRecording reported yet. */
    private suspend fun TestScope.startReachingTheMovieOutput(
        engine: CameraEngine,
    ): Pair<Deferred<CameraKResult<String>>, FakeMovieRecorder.Start> {
        val start = async { engine.startRecording(VideoConfiguration()) }
        repeat(50) { if (driver.movieRecorder.starts.isEmpty()) driver.settle() }
        val begun = driver.movieRecorder.starts.single()
        written += begun.url.path!!
        return start to begun
    }
}

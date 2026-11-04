@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.EngineRecordingEvent
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import platform.AVFoundation.AVErrorMaximumDurationReached
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A recording AVFoundation ends by itself (a limit, an interruption) reaches the engine's recording
 * events, which the holder collects for its whole life (ios-recording-lifecycle,
 * recording-terminal-events-dropped): nobody has to call stopRecording to learn of it.
 */
class RecordingEventFlowTest {
    private val driver = AppleEngineDriver()
    private val written = mutableListOf<String>()

    @AfterTest
    fun tearDown() = removeFiles(written)

    @Test
    fun selfEndedRecordingReachesTheEngineFlowWithoutStop() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        val events = recordingEventsOf(engine)
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        val recording = startEngineRecording(driver, engine).also { written += it.path }

        finishRecording(driver, recording, movieError(AVErrorMaximumDurationReached, successfullyFinished = true))

        assertEquals(
            listOf(EngineRecordingEvent.Started::class, EngineRecordingEvent.Finalized::class),
            events.values.map { it::class },
            "the recording's events",
        )
        val finalized = events.finalized().single()
        assertEquals(RecordingEndReason.MAX_DURATION, finalized.reason)
        assertEquals(recording.path, assertIs<CameraKResult.Success<RecordedVideo>>(finalized.result).value.path)
        assertEquals(0, driver.movieRecorder.stops, "the engine asked the movie output to stop")
        engine.close()
    }
}

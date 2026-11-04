@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.EngineRecordingEvent
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * AVCaptureMovieFileOutput pauses from iOS 18: pauseRecording returns once the movie output reports the
 * recording paused and the engine emits Paused, and resumeRecording likewise with Resumed. Below 18 a
 * pause is Unsupported(PAUSE_RESUME) and the output is never asked.
 */
class PauseResumeTest {
    private val written = mutableListOf<String>()

    @AfterTest
    fun tearDown() = removeFiles(written)

    @Test
    fun pauseOnIos18ReturnsOnceTheMovieOutputIsPausedAndEmitsPaused() {
        val driver = AppleEngineDriver(osVersion = 18)
        driver.runCase {
            val engine = driver.createEngine()
            driver.settle()
            val events = recordingEventsOf(engine)
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            val recording = startEngineRecording(driver, engine).also { written += it.path }

            assertEquals(CameraKResult.Success(Unit), engine.pauseRecording())

            assertEquals(
                true,
                driver.movieRecorder.isPaused(recording.start.output),
                "the movie output when pause returned",
            )
            driver.settle()
            assertEquals(EngineRecordingEvent.Paused, events.values.last(), "the engine's last recording event")
            engine.close()
        }
    }

    @Test
    fun resumeEmitsResumed() {
        val driver = AppleEngineDriver(osVersion = 18)
        driver.runCase {
            val engine = driver.createEngine()
            driver.settle()
            val events = recordingEventsOf(engine)
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            val recording = startEngineRecording(driver, engine).also { written += it.path }
            assertEquals(CameraKResult.Success(Unit), engine.pauseRecording())

            assertEquals(CameraKResult.Success(Unit), engine.resumeRecording())

            assertEquals(
                false,
                driver.movieRecorder.isPaused(recording.start.output),
                "the movie output when resume returned",
            )
            driver.settle()
            assertEquals(
                listOf(EngineRecordingEvent.Paused, EngineRecordingEvent.Resumed),
                events.values.filter { it is EngineRecordingEvent.Paused || it is EngineRecordingEvent.Resumed },
            )
            engine.close()
        }
    }

    @Test
    fun belowIos18PauseIsUnsupportedPauseResume() {
        val driver = AppleEngineDriver(osVersion = 17)
        driver.runCase {
            val engine = driver.createEngine()
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            startEngineRecording(driver, engine).also { written += it.path }

            val refused = engine.pauseRecording()

            val error = assertIs<CameraKError.Unsupported>(assertIs<CameraKResult.Failure>(refused).error)
            assertEquals(CameraFeature.PAUSE_RESUME, error.feature)
            assertEquals(0, driver.movieRecorder.pauses, "a movie output that cannot pause was asked to")
            engine.close()
        }
    }
}

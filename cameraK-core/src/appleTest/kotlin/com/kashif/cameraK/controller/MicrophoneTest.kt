@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.EngineRecordingEvent
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.async
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A recording with audio needs NSMicrophoneUsageDescription in the app's Info.plist: TCC kills an app
 * that touches the microphone without it. So the engine checks the key first and fails fast with
 * Unsupported(AUDIO), naming the key, before any microphone call (design §5). A microphone the session
 * does not take, or whose audio connection is not active, leaves the recording without audio, and the
 * recording says so: hasAudio is false.
 */
class MicrophoneTest {
    private val written = mutableListOf<String>()

    @AfterTest
    fun tearDown() = removeFiles(written)

    @Test
    fun enableAudioWithoutUsageDescriptionFailsFast() {
        val driver = AppleEngineDriver(hasUsageDescription = { false })
        driver.runCase {
            val engine = driver.createEngine()
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

            val refused = engine.startRecording(VideoConfiguration(enableAudio = true))

            val error = assertIs<CameraKError.Unsupported>(assertIs<CameraKResult.Failure>(refused).error)
            assertEquals(CameraFeature.AUDIO, error.feature)
            assertIs<IllegalStateException>(error.cause, "the cause")
            assertTrue("NSMicrophoneUsageDescription" in error.cause?.message.orEmpty(), "the cause names the key")
            driver.settle()
            assertEquals(0, driver.microphoneRequests, "the microphone was asked for its input")
            assertFalse(driver.microphone in driver.session.inputs, "a microphone input was added")
            assertEquals(0, driver.movieRecorder.starts.size, "the movie output was started")
            engine.close()
        }
    }

    @Test
    fun prepareWithoutUsageDescriptionIsUnsupportedAudio() {
        val driver = AppleEngineDriver(hasUsageDescription = { false })
        driver.runCase {
            val engine = driver.createEngine()
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

            val refused = engine.prepareRecording(VideoConfiguration(enableAudio = true))

            val error = assertIs<CameraKError.Unsupported>(assertIs<CameraKResult.Failure>(refused).error)
            assertEquals(CameraFeature.AUDIO, error.feature)
            driver.settle()
            assertEquals(0, driver.microphoneRequests, "the microphone was asked for its input")
            engine.close()
        }
    }

    @Test
    fun audioDroppedReportsHasAudioFalse() {
        val driver = AppleEngineDriver()
        driver.runCase {
            val engine = driver.createEngine()
            driver.settle()
            val events = recordingEventsOf(engine)
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            driver.session.refuse(driver.microphone)

            val recording = startEngineRecording(driver, engine, VideoConfiguration(enableAudio = true))
                .also { written += it.path }

            val started = events.values.filterIsInstance<EngineRecordingEvent.Started>().single()
            assertFalse(started.hasAudio, "a recording whose microphone the session refused reported audio")
            val stop = async { engine.stopRecording() }
            driver.settle()
            finishRecording(driver, recording)
            repeat(20) { if (!stop.isCompleted) driver.settle() }
            val video = assertIs<CameraKResult.Success<RecordedVideo>>(stop.await()).value
            assertFalse(video.hasAudio, "the finished recording reported an audio track")
            engine.close()
        }
    }

    /**
     * hasAudio comes from the movie output's audio connection, not from the microphone having been
     * added: a microphone the session took whose connection is not active records no audio track.
     */
    @Test
    fun anInactiveAudioConnectionReportsHasAudioFalse() {
        val driver = AppleEngineDriver()
        driver.runCase {
            val engine = driver.createEngine()
            driver.settle()
            val events = recordingEventsOf(engine)
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            driver.movieRecorder.audioConnectionActive = false

            val recording = startEngineRecording(driver, engine, VideoConfiguration(enableAudio = true))
                .also { written += it.path }

            assertTrue(driver.microphone in driver.session.inputs, "the microphone was not added")
            val started = events.values.filterIsInstance<EngineRecordingEvent.Started>().single()
            assertFalse(started.hasAudio, "a recording without an active audio connection reported audio")
            val stop = async { engine.stopRecording() }
            driver.settle()
            finishRecording(driver, recording)
            repeat(20) { if (!stop.isCompleted) driver.settle() }
            val video = assertIs<CameraKResult.Success<RecordedVideo>>(stop.await()).value
            assertFalse(video.hasAudio, "the finished recording reported an audio track")
            engine.close()
        }
    }

    @Test
    fun aRecordingWithItsMicrophoneHasAudio() {
        val driver = AppleEngineDriver()
        driver.runCase {
            val engine = driver.createEngine()
            driver.settle()
            val events = recordingEventsOf(engine)
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

            startEngineRecording(driver, engine, VideoConfiguration(enableAudio = true)).also { written += it.path }

            assertTrue(driver.microphone in driver.session.inputs, "the microphone was not added")
            assertTrue(events.values.filterIsInstance<EngineRecordingEvent.Started>().single().hasAudio)
            engine.close()
        }
    }
}

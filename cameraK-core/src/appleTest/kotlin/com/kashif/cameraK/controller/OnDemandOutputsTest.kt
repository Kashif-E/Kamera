@file:OptIn(com.kashif.cameraK.utils.CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMovieFileOutput
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A photo-only session carries no movie or metadata output, and a recording's movie output and
 * microphone leave the session when it ends.
 */
class OnDemandOutputsTest {
    private val back = FakeDevice()
    private val session = RecordingSession()
    private val custom = CustomCameraController(
        qualityPrioritization = QualityPrioritization.NONE,
        sessionFactory = { session },
        cameraDevices = { _, _ -> back },
    )
    private val engine = AppleCameraEngine(
        CameraConfiguration { aspectRatio = AspectRatio.RATIO_4_3 },
        CameraKEnvironment(),
        custom,
        // The test bundle's Info.plist has no NSMicrophoneUsageDescription, which an app recording audio has.
        hasUsageDescription = { true },
    )

    private fun setUpSession() {
        custom.setupSession()
        drainSessionThenMain(custom)
        assertEquals(listOf(back.input), session.inputs, "setup did not attach the camera")
    }

    private fun movieOutputs() = session.outputs.filterIsInstance<AVCaptureMovieFileOutput>()

    private fun metadataOutputs() = session.outputs.filterIsInstance<AVCaptureMetadataOutput>()

    @AfterTest
    fun tearDown() {
        engine.close()
        drainSessionThenMain(custom)
    }

    @Test
    fun aPhotoOnlySessionHasNoMovieOrMetadataOutput() {
        setUpSession()

        assertEquals(emptyList(), movieOutputs(), "a photo-only session carries a movie file output")
        assertEquals(emptyList(), metadataOutputs(), "a session with no scanner carries a metadata output")
    }

    @Test
    fun aRecordingsMovieOutputAndMicrophoneLeaveTheSessionWhenItEnds() {
        val recorder = FakeMovieRecorder()
        val microphone = captureInputWithoutCamera()
        engine.movieRecorder = recorder
        engine.microphoneInput = { Result.success(microphone) }
        val scope = MainScope()
        try {
            scope.launch { engine.start(backCamera()) }
            awaitTrue("the session to start") { session.isRunning }
            awaitTrue("the engine to report Open") { engine.state.value == EngineState.Open }
            scope.launch { engine.startRecording(VideoConfiguration { enableAudio = true }) }
            awaitTrue("the recording to start on the movie output") { recorder.starts.isNotEmpty() }
            val started = recorder.starts.single()
            assertTrue(started.output in session.outputs, "the recording's movie output is not attached")
            assertTrue(microphone in session.inputs, "the recording's microphone is not attached")

            recorder.reportStarted(started)
            recorder.reportFinished(started, error = null)
            drainSessionThenMain(custom)

            assertFalse(started.output in session.outputs, "the movie output stayed attached after the recording ended")
            assertEquals(listOf(back.input), session.inputs, "the microphone stayed attached after the recording ended")
        } finally {
            scope.cancel()
        }
    }
}

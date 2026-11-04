@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import platform.AVFoundation.AVCaptureMovieFileOutput
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * prepareRecording adds the movie output (and the microphone, for audio) ahead of time, in one
 * configuration transaction (design §5), so the start that follows commits no configuration change:
 * a commit reconfigures the session's graph and glitches the preview. A prepared output stays until
 * the camera stops.
 */
class PrepareRecordingTest {
    private val driver = AppleEngineDriver()
    private val written = mutableListOf<String>()

    @AfterTest
    fun tearDown() = removeFiles(written)

    @Test
    fun preparedStartCommitsNoConfiguration() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        val config = VideoConfiguration()

        val beforePrepare = driver.session.configurationsBegun
        assertEquals(CameraKResult.Success(Unit), engine.prepareRecording(config))
        driver.settle()
        assertEquals(1, driver.session.configurationsBegun - beforePrepare, "transactions prepareRecording committed")
        assertTrue(driver.session.outputs.any { it is AVCaptureMovieFileOutput }, "prepare added no movie output")

        val beforeStart = driver.session.configurationsBegun
        val recording = startEngineRecording(driver, engine, config).also { written += it.path }

        assertEquals(beforeStart, driver.session.configurationsBegun, "the prepared start committed a configuration")
        assertTrue(recording.start.output in driver.session.outputs, "the start recorded to another output")
        engine.close()
    }
}

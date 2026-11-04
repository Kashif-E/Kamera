@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import platform.AVFoundation.AVCaptureMovieFileOutput
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The movie calls run on the session queue (design §2.4), after the transaction that added the movie
 * output has committed: starting a recording inside an open configuration, or off the queue while it
 * changes the session, is what AVFoundation does not allow.
 */
class MovieOutputQueueTest {
    private val driver = AppleEngineDriver()
    private val written = mutableListOf<String>()

    @AfterTest
    fun tearDown() = removeFiles(written)

    @Test
    fun startRunsOnSessionQueueAfterCommit() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        var openAtStart = -1
        var outputAttached = false
        driver.movieRecorder.onStart = {
            openAtStart = driver.session.openConfigurations
            outputAttached = driver.session.outputs.any { it is AVCaptureMovieFileOutput }
        }

        val recording = startEngineRecording(driver, engine).also { written += it.path }

        assertEquals("com.kashif.cameraK.session", recording.start.queue, "the queue the recording started on")
        assertEquals(0, openAtStart, "configuration transactions open when the recording started")
        assertTrue(outputAttached, "the movie output was not in the session when the recording started")
        engine.close()
    }
}

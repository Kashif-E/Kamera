@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The session reconfigures the app's AVAudioSession for its audio input unless told not to, and its
 * default is to do so. CameraK sets automaticallyConfiguresApplicationAudioSession explicitly: true only
 * while an audio recording is configured, so a camera with no audio (photos, a video-only recording)
 * never touches the app's audio, whatever music or call it is playing (design §5).
 */
class AudioSessionPolicyTest {
    private val driver = AppleEngineDriver()
    private val written = mutableListOf<String>()

    @AfterTest
    fun tearDown() = removeFiles(written)

    @Test
    fun videoOnlyRecordingNeverTouchesTheAppAudioSession() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        assertFalse(driver.session.configuresTheAppAudioSession, "a photo session configures the app's audio session")

        var duringStart: Boolean? = null
        driver.movieRecorder.onStart = { duringStart = driver.session.configuresTheAppAudioSession }
        val recording = startEngineRecording(driver, engine, VideoConfiguration(enableAudio = false))
            .also { written += it.path }
        finishRecording(driver, recording)

        assertEquals(false, duringStart, "a video-only recording let the session configure the app's audio session")
        assertFalse(true in driver.session.audioSessionConfigurations, "a camera with no audio turned it on")
        engine.close()
    }

    @Test
    fun anAudioRecordingConfiguresTheAppAudioSessionOnlyWhileConfigured() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

        var duringStart: Boolean? = null
        driver.movieRecorder.onStart = { duringStart = driver.session.configuresTheAppAudioSession }
        val recording = startEngineRecording(driver, engine, VideoConfiguration(enableAudio = true))
            .also { written += it.path }
        assertEquals(true, duringStart, "an audio recording without the session's audio configuration")

        finishRecording(driver, recording)
        driver.settle()
        assertFalse(driver.session.configuresTheAppAudioSession, "the audio configuration outlived the recording")
        assertTrue(driver.session.audioSessionConfigurations.isNotEmpty())
        engine.close()
    }
}

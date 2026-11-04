@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import com.kashif.cameraK.video.VideoQuality
import platform.AVFoundation.AVCaptureSessionPreset1920x1080
import platform.AVFoundation.AVCaptureSessionPreset3840x2160
import platform.AVFoundation.AVCaptureSessionPresetPhoto
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A supported VideoConfiguration.quality is applied in the movie transaction (w6 decision G2, CC-24):
 * the session runs the quality's preset from the movie's start (FHD is 1920x1080) and returns to the
 * still preset after the recording's Finalized. A quality the bound camera cannot record is
 * Unsupported(VIDEO_QUALITY), and nothing is changed.
 */
class VideoQualityTest {
    private val driver = AppleEngineDriver()
    private val written = mutableListOf<String>()

    @AfterTest
    fun tearDown() = removeFiles(written)

    @Test
    fun fhdRunsItsPresetWhileRecordingAndTheStillPresetAfterFinalized() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        assertEquals(AVCaptureSessionPresetPhoto, driver.session.presets.last(), "the still preset")
        var presetAtStart: String? = null
        driver.movieRecorder.onStart = { presetAtStart = driver.session.presets.last() }

        val recording = startEngineRecording(driver, engine, VideoConfiguration(quality = VideoQuality.FHD))
            .also { written += it.path }
        assertEquals(AVCaptureSessionPreset1920x1080, presetAtStart, "the preset when the movie started")

        finishRecording(driver, recording)
        driver.settle()
        assertEquals(AVCaptureSessionPresetPhoto, driver.session.presets.last(), "the preset after Finalized")
        engine.close()
    }

    @Test
    fun aQualityTheCameraCannotRecordIsUnsupported() = driver.runCase {
        driver.back.supportedPresets = setOf(AVCaptureSessionPresetPhoto!!, AVCaptureSessionPreset1920x1080!!)
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        val presets = driver.session.presets

        val refused = engine.startRecording(VideoConfiguration(quality = VideoQuality.UHD))

        val error = assertIs<CameraKError.Unsupported>(assertIs<CameraKResult.Failure>(refused).error)
        assertEquals(CameraFeature.VIDEO_QUALITY, error.feature)
        driver.settle()
        assertEquals(presets, driver.session.presets, "a refused quality changed the preset")
        assertEquals(false, AVCaptureSessionPreset3840x2160 in driver.session.presets)
        assertEquals(0, driver.movieRecorder.starts.size)
        engine.close()
    }
}

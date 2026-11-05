@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoQuality
import platform.AVFoundation.AVCaptureSessionPreset1920x1080
import platform.AVFoundation.AVCaptureSessionPreset640x480
import platform.AVFoundation.AVCaptureSessionPresetPhoto
import platform.AVFoundation.AVVideoCodecTypeHEVC
import platform.AVFoundation.AVVideoCodecTypeJPEG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The engine enumerates its cameras on its own scope after construction, never inside the
 * constructor, and fills the first real capabilities: the 1.x enumerator's lenses, flash and torch
 * modes per camera (a camera with neither lists only OFF), and pause and resume from iOS 18, where
 * AVCaptureMovieFileOutput can pause. From I3' (design §6.3): JPEG and PNG, and HEIC where the photo
 * output can encode HEVC; every aspect ratio with a true 1:1; the recording qualities whose preset the
 * bound camera delivers, refreshed after a lens switch and empty before the first bind; and no video
 * bitrate, which AVCaptureMovieFileOutput takes from the preset.
 */
class AppleCapabilitiesTest {
    @Test
    fun enumeratedOnTheEngineScopeNotInTheConstructor() {
        val driver = AppleEngineDriver()
        driver.runCase {
            val engine = driver.createEngine()
            assertEquals(0, driver.enumerationsRun, "the constructor enumerated the cameras")
            assertEquals(CameraCapabilities.EMPTY, engine.capabilities.value, "capabilities before enumeration")

            driver.settle()

            assertEquals(1, driver.enumerationsRun, "enumerations after the engine's scope ran")
            val capabilities = engine.capabilities.value
            assertEquals(driver.lenses, capabilities.allLenses)
            assertEquals(setOf(FlashMode.OFF), capabilities.supportedFlashModes(AppleEngineDriver.BACK_ID))
            assertEquals(setOf(TorchMode.OFF), capabilities.supportedTorchModes(AppleEngineDriver.BACK_ID))
            engine.close()
        }
    }

    @Test
    fun pauseResumeOnlyFromIos18() {
        for ((os, supported) in listOf(17 to false, 18 to true)) {
            val driver = AppleEngineDriver(osVersion = os)
            driver.runCase {
                val engine = driver.createEngine()
                driver.settle()
                val pause = engine.capabilities.value.supportsPauseResume
                if (supported) {
                    assertTrue(pause, "iOS $os can pause a recording")
                } else {
                    assertFalse(pause, "iOS $os cannot pause a recording")
                }
                engine.close()
            }
        }
    }

    @Test
    fun aLensWithAFlashAndATorchListsItsModesAndOneWithoutListsOnlyOff() {
        val driver = AppleEngineDriver(adjustableBack = true)
        driver.runCase {
            val engine = driver.createEngine()
            driver.settle()

            val capabilities = engine.capabilities.value
            assertEquals(
                setOf(FlashMode.OFF, FlashMode.AUTO, FlashMode.ON),
                capabilities.supportedFlashModes(AppleEngineDriver.BACK_ID),
                "flash modes of the camera with a flash",
            )
            assertEquals(
                setOf(TorchMode.OFF, TorchMode.ON),
                capabilities.supportedTorchModes(AppleEngineDriver.BACK_ID),
                "torch modes of the camera with a torch",
            )
            assertEquals(setOf(FlashMode.OFF), capabilities.supportedFlashModes(AppleEngineDriver.FRONT_ID))
            assertEquals(setOf(TorchMode.OFF), capabilities.supportedTorchModes(AppleEngineDriver.FRONT_ID))
            engine.close()
        }
    }

    @Test
    fun heicListedOnlyWhenTheHevcCodecIsAvailable() {
        for ((codecs, formats) in listOf(
            listOf(AVVideoCodecTypeHEVC!!, AVVideoCodecTypeJPEG!!) to
                setOf(ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.HEIC),
            listOf(AVVideoCodecTypeJPEG!!) to setOf(ImageFormat.JPEG, ImageFormat.PNG),
        )) {
            val driver = AppleEngineDriver()
            driver.photoCapturer.codecTypes = codecs
            driver.runCase {
                val engine = driver.createEngine()
                driver.settle()
                assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
                driver.settle()

                assertEquals(formats, engine.capabilities.value.supportedImageFormats, "with codecs $codecs")
                engine.close()
            }
        }
    }

    @Test
    fun squareCaptureSupported() {
        val driver = AppleEngineDriver()
        driver.runCase {
            val engine = driver.createEngine()
            driver.settle()

            val capabilities = engine.capabilities.value
            assertTrue(capabilities.supportsSquareCapture, "a true 1:1 capture")
            assertEquals(AspectRatio.entries.toSet(), capabilities.supportedAspectRatios)
            engine.close()
        }
    }

    @Test
    fun videoQualitiesAreThePresetsTheBoundDeviceSupports() {
        val driver = AppleEngineDriver()
        driver.back.supportedPresets = setOf(
            AVCaptureSessionPresetPhoto!!,
            AVCaptureSessionPreset640x480!!,
            AVCaptureSessionPreset1920x1080!!,
        )
        driver.runCase {
            val engine = driver.createEngine()
            driver.settle()
            assertEquals(emptySet(), engine.capabilities.value.supportedVideoQualities, "before the first bind")

            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            driver.settle()
            assertEquals(setOf(VideoQuality.SD, VideoQuality.FHD), engine.capabilities.value.supportedVideoQualities)

            assertIs<CameraKResult.Success<*>>(engine.selectCamera(CameraSelection.ById(AppleEngineDriver.FRONT_ID)))
            driver.settle()
            assertEquals(
                VideoQuality.entries.toSet(),
                engine.capabilities.value.supportedVideoQualities,
                "the front camera's qualities after the switch",
            )
            engine.close()
        }
    }

    @Test
    fun videoBitrateIsNotSupported() {
        val driver = AppleEngineDriver()
        driver.runCase {
            val engine = driver.createEngine()
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            driver.settle()

            assertFalse(
                engine.capabilities.value.supportsVideoBitrate,
                "AVCaptureMovieFileOutput records at its preset's rate",
            )
            engine.close()
        }
    }

    /** iOS analysis frames are 420f only (I4', design §8): RGBA_8888 fails the start instead. */
    @Test
    fun analysisFormatsYuvOnly() {
        val driver = AppleEngineDriver()
        driver.runCase {
            val engine = driver.createEngine()
            driver.settle()
            assertEquals(setOf(FrameFormat.YUV_420), engine.capabilities.value.supportedAnalysisFormats)
            engine.close()
        }
    }
}

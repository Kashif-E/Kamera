@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.engine

import dev.kashifmehmood.camerak.capabilities.CameraCapabilities
import dev.kashifmehmood.camerak.capabilities.LensInfo
import dev.kashifmehmood.camerak.enums.AspectRatio
import dev.kashifmehmood.camerak.enums.CameraDeviceType
import dev.kashifmehmood.camerak.enums.CameraLens
import dev.kashifmehmood.camerak.enums.FlashMode
import dev.kashifmehmood.camerak.enums.ImageFormat
import dev.kashifmehmood.camerak.enums.TorchMode
import dev.kashifmehmood.camerak.error.CameraFeature
import dev.kashifmehmood.camerak.error.CameraKError
import dev.kashifmehmood.camerak.frames.FrameFormat
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import dev.kashifmehmood.camerak.video.VideoConfiguration
import dev.kashifmehmood.camerak.video.VideoQuality
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The engine enumerates its cameras on its own scope after construction, never in the constructor,
 * and reports the first real values of §6.3: flash and torch from each lens's flash unit, and pause
 * and resume, which CameraX's Recorder supports. From A3' the video qualities are the bound camera's
 * own, read through `CameraXBackend.videoQualities` after each bind, the bitrate is the quality's and
 * applied, and the image formats are JPEG and PNG.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidCapabilitiesTest {
    private val withFlash = LensInfo("0", CameraDeviceType.WIDE_ANGLE, CameraLens.BACK, 1f, 8f, true, false)
    private val withoutFlash = LensInfo("1", CameraDeviceType.WIDE_ANGLE, CameraLens.FRONT, 1f, 1f, false, false)
    private val platform = FakeAndroidPlatform(listOf(withFlash, withoutFlash))

    @Test
    fun enumeratedOnTheEngineScopeNotInTheConstructor() = runTest {
        val engine = platform.engine(testScheduler)
        assertTrue(platform.enumerations.isEmpty(), "the constructor enumerated the cameras")
        assertEquals(CameraCapabilities.EMPTY, engine.capabilities.value, "before enumeration")

        platform.settle(testScheduler)
        assertEquals(1, platform.enumerations.size, "enumerations after the engine's scope ran")
        assertEquals(listOf(withFlash, withoutFlash), engine.capabilities.value.allLenses)
        engine.closeAndJoin()
    }

    @Test
    fun pauseResumeSupported() = runTest {
        val engine = platform.engine(testScheduler)
        platform.settle(testScheduler)
        assertTrue(engine.capabilities.value.supportsPauseResume)
        engine.closeAndJoin()
    }

    @Test
    fun flashModesFromHasFlash() = runTest {
        val engine = platform.engine(testScheduler)
        platform.settle(testScheduler)
        val capabilities = engine.capabilities.value

        assertEquals(setOf(FlashMode.OFF, FlashMode.AUTO, FlashMode.ON), capabilities.supportedFlashModes(withFlash.id))
        assertEquals(setOf(TorchMode.OFF, TorchMode.ON), capabilities.supportedTorchModes(withFlash.id))
        assertEquals(setOf(FlashMode.OFF), capabilities.supportedFlashModes(withoutFlash.id))
        assertEquals(setOf(TorchMode.OFF), capabilities.supportedTorchModes(withoutFlash.id))
        engine.closeAndJoin()
    }

    @Suppress("DEPRECATION")
    @Test
    fun videoQualitiesFromTheRecorderImageFormatsJpegAndPng() = runTest {
        platform.backend.supportedQualities = setOf(VideoQuality.HD, VideoQuality.FHD)
        val engine = platform.engine(testScheduler)
        platform.settle(testScheduler)
        assertEquals(emptySet(), engine.capabilities.value.supportedVideoQualities, "before the first bind")

        open(platform, engine)

        val capabilities = engine.capabilities.value
        assertEquals(setOf(VideoQuality.HD, VideoQuality.FHD), capabilities.supportedVideoQualities)
        assertTrue(capabilities.supportsVideoBitrate, "the quality's bitrate is applied")
        assertEquals(setOf(ImageFormat.JPEG, ImageFormat.PNG), capabilities.supportedImageFormats)
        assertEquals(
            setOf(AspectRatio.RATIO_4_3, AspectRatio.RATIO_16_9, AspectRatio.RATIO_9_16, AspectRatio.RATIO_1_1),
            capabilities.supportedAspectRatios,
        )
        engine.closeAndJoin()
    }

    @Test
    fun videoQualitiesAreTheBoundCamerasAndRefreshAfterASwitch() = runTest {
        platform.backend.qualitiesById = mapOf(
            withFlash.id to setOf(VideoQuality.UHD, VideoQuality.FHD),
            withoutFlash.id to setOf(VideoQuality.HD),
        )
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        assertEquals(setOf(VideoQuality.UHD, VideoQuality.FHD), engine.capabilities.value.supportedVideoQualities)

        assertIs<CameraKResult.Success<*>>(engine.selectCamera(CameraSelection.ById(withoutFlash.id)))
        platform.settle(testScheduler)

        assertEquals(setOf(VideoQuality.HD), engine.capabilities.value.supportedVideoQualities, "after the switch")
        engine.closeAndJoin()
    }

    @Test
    fun qualityOutsideTheBoundCamerasSetIsUnsupportedVideoQuality() = runTest {
        platform.backend.supportedQualities = setOf(VideoQuality.HD)
        val engine = platform.engine(testScheduler)
        open(platform, engine)

        val result = engine.startRecording(VideoConfiguration { quality = VideoQuality.UHD })

        val error = assertIs<CameraKError.Unsupported>(assertIs<CameraKResult.Failure>(result).error)
        assertEquals(CameraFeature.VIDEO_QUALITY, error.feature)
        assertTrue(platform.backend.recordings.isEmpty(), "an unsupported quality started recording")
        engine.closeAndJoin()
    }

    // A recording that leaves the quality at its default is not refused for it: a camera that
    // records no FHD (the emulator's, many front cameras) records the nearest quality below it.
    @Test
    fun theDefaultQualityOnACameraWithoutItRecordsTheNearestLowerQuality() = runTest {
        platform.backend.supportedQualities = setOf(VideoQuality.SD, VideoQuality.HD, VideoQuality.UHD)
        val engine = platform.engine(testScheduler)
        open(platform, engine)

        startRecording(platform, engine, VideoConfiguration { enableAudio = false })

        val build = platform.backend.videoCaptureBuilds.single()
        assertEquals(VideoQuality.HD, build.quality, "the quality recorded")
        assertEquals(VideoQuality.HD.bitrateBps, build.bitrateBps, "the Recorder's target bitrate")
        platform.backend.recordings.single().finalize()
        platform.settle(testScheduler)
        engine.closeAndJoin()
    }

    @Test
    fun recorderTargetBitrateIsTheQualitysBitrate() = runTest {
        val engine = platform.engine(testScheduler)
        open(platform, engine)

        startRecording(platform, engine, VideoConfiguration { quality = VideoQuality.HD })

        val build = platform.backend.videoCaptureBuilds.single()
        assertEquals(VideoQuality.HD, build.quality)
        assertEquals(VideoQuality.HD.bitrateBps, build.bitrateBps, "the Recorder's target bitrate")
        platform.backend.recordings.single().finalize()
        platform.settle(testScheduler)
        engine.closeAndJoin()
    }

    @Test
    fun analysisFormatsYuvAndRgba() = runTest {
        val engine = platform.engine(testScheduler)
        platform.settle(testScheduler)
        assertEquals(
            setOf(FrameFormat.YUV_420, FrameFormat.RGBA_8888),
            engine.capabilities.value.supportedAnalysisFormats,
            "ImageAnalysis outputs YUV_420_888 and RGBA_8888",
        )
        engine.closeAndJoin()
    }
}

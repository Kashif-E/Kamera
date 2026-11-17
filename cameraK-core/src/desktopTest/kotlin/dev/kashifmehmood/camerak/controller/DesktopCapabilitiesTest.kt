@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.capabilities.CameraCapabilities
import dev.kashifmehmood.camerak.capabilities.LensInfo
import dev.kashifmehmood.camerak.capture.RecordedVideo
import dev.kashifmehmood.camerak.enums.AspectRatio
import dev.kashifmehmood.camerak.enums.CameraDeviceType
import dev.kashifmehmood.camerak.enums.CameraLens
import dev.kashifmehmood.camerak.enums.FlashMode
import dev.kashifmehmood.camerak.enums.ImageFormat
import dev.kashifmehmood.camerak.enums.TorchMode
import dev.kashifmehmood.camerak.frames.FrameFormat
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.state.CameraConfiguration
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import dev.kashifmehmood.camerak.video.VideoConfiguration
import dev.kashifmehmood.camerak.video.VideoQuality
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.bytedeco.javacv.FFmpegFrameRecorder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The desktop engine enumerates its camera on its own scope after construction, never in the
 * constructor (§6.3), and reports the first real capability values: the 1.x enumerator's one
 * camera, OFF-only flash and torch from `hasFlash`, and pause and resume. From D3' (§6.3): JPEG and
 * PNG stills without HEIC, all four video qualities, and the quality's bitrate as the encoder's target;
 * from D4', every aspect ratio and both analysis formats.
 */
class DesktopCapabilitiesTest {
    @Test
    fun enumeratedOnTheEngineScopeNotInTheConstructor() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        var enumerations = 0
        val sut = DesktopCameraEngine(
            CameraConfiguration(),
            rig.environment(),
            rig.source,
            enumerate = {
                enumerations++
                desktopCapabilities()
            },
        )
        try {
            assertEquals(0, enumerations, "the constructor enumerated the cameras")
            assertSame(CameraCapabilities.EMPTY, sut.capabilities.value, "capabilities before enumeration")

            runCurrent()

            assertEquals(1, enumerations)
            val camera = LensInfo(
                id = "",
                deviceType = CameraDeviceType.DEFAULT,
                lens = CameraLens.BACK,
                minZoom = 1f,
                maxZoom = 1f,
                hasFlash = false,
                isLogical = false,
            )
            assertEquals(listOf(camera), sut.capabilities.value.allLenses)
            assertEquals(setOf(FlashMode.OFF), sut.capabilities.value.supportedFlashModes(camera.id))
            assertEquals(setOf(TorchMode.OFF), sut.capabilities.value.supportedTorchModes(camera.id))
        } finally {
            sut.close()
        }
    }

    @Test
    fun pauseResumeSupported() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val sut = rig.engine()
        try {
            rig.settle()

            assertTrue(sut.capabilities.value.supportsPauseResume, "desktop pauses through RecordingClock")
        } finally {
            sut.close()
        }
    }

    @Test
    fun imageFormatsJpegAndPngWithoutHeic() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val sut = rig.engine()
        try {
            rig.settle()

            assertEquals(setOf(ImageFormat.JPEG, ImageFormat.PNG), sut.capabilities.value.supportedImageFormats)
        } finally {
            sut.close()
        }
    }

    // D4' (§6.3): every aspect ratio, 1:1 included, is a crop in the producer's blit, and analysis
    // frames come in either session format.
    @Test
    fun everyAspectRatioAndBothAnalysisFormats() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val sut = rig.engine()
        try {
            rig.settle()

            assertEquals(AspectRatio.entries.toSet(), sut.capabilities.value.supportedAspectRatios)
            assertTrue(sut.capabilities.value.supportsSquareCapture, "1:1 is a crop like the others")
            assertEquals(FrameFormat.entries.toSet(), sut.capabilities.value.supportedAnalysisFormats)
        } finally {
            sut.close()
        }
    }

    @Test
    fun videoQualitiesAllFourAndTheRecorderTakesTheQualitysBitrate() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        val output = File(rig.home, "bitrate.mp4")
        val encoder = createFrameRecorder(output.path, VideoConfiguration { quality = VideoQuality.HD })
        try {
            rig.settle()
            assertEquals(VideoQuality.entries.toSet(), sut.capabilities.value.supportedVideoQualities)
            assertTrue(sut.capabilities.value.supportsVideoBitrate, "the encoder takes the quality's bitrate")
            assertEquals(VideoQuality.HD.bitrateBps, (encoder as FFmpegFrameRecorder).videoBitrate, "encoder target")

            steps.open(sut)
            steps.at(1_000)
            steps.startRecording(sut, steps.video().copy { quality = VideoQuality.SD })
            steps.frameAt(1_040)
            val video = assertIs<CameraKResult.Success<RecordedVideo>>(steps.stopRecording(sut)).value

            assertEquals(VideoQuality.SD.bitrateBps, video.bitrateBps, "the bitrate the recording reports")
        } finally {
            encoder.release()
            sut.close()
            rig.settle()
        }
    }
}

@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import com.kashif.cameraK.video.VideoQuality
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
 * PNG stills without HEIC, all four video qualities, and the quality's bitrate as the encoder's target.
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

    @Test
    fun videoQualitiesAllFourAndTheRecorderTakesTheQualitysBitrate() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        val output = File(rig.home, "bitrate.mp4")
        val encoder = createFrameRecorder(output.path, VideoConfiguration(quality = VideoQuality.HD))
        try {
            rig.settle()
            assertEquals(VideoQuality.entries.toSet(), sut.capabilities.value.supportedVideoQualities)
            assertTrue(sut.capabilities.value.supportsVideoBitrate, "the encoder takes the quality's bitrate")
            assertEquals(VideoQuality.HD.bitrateBps, (encoder as FFmpegFrameRecorder).videoBitrate, "encoder target")

            steps.open(sut)
            steps.at(1_000)
            steps.startRecording(sut, steps.video().copy(quality = VideoQuality.SD))
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

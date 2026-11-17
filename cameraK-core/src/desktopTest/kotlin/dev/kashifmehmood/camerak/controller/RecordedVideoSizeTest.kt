@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.capture.RecordedVideo
import dev.kashifmehmood.camerak.enums.AspectRatio
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.state.CameraConfiguration
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import dev.kashifmehmood.camerak.video.VideoQuality
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * A desktop recording is encoded at the camera's own frame size, whatever the quality asks for
 * (no frame is rescaled on the way in), and `RecordedVideo` reports the width and height written,
 * with no audio track.
 */
class RecordedVideoSizeTest {
    @Test
    fun reportsTheSizeWritten() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        // A 4:3 configuration for the 4:3 camera, so the blit crops nothing (D4').
        val sut = rig.engine(CameraConfiguration { aspectRatio = AspectRatio.RATIO_4_3 })
        try {
            // The scripted camera's frames are 64 x 48.
            steps.open(sut)
            steps.at(1_000)
            val path = steps.startRecording(sut, steps.video().copy { quality = VideoQuality.FHD })
            steps.frameAt(1_040)
            val video = assertIs<CameraKResult.Success<RecordedVideo>>(steps.stopRecording(sut)).value

            assertEquals(path, video.path)
            assertEquals(64 to 48, video.width to video.height, "the size the recording reports")
            assertEquals(64 to 48, rig.recorders.single().sizeAtStart, "the size the recorder was started at")
            assertFalse(video.hasAudio)
        } finally {
            sut.close()
            rig.settle()
        }
    }
}

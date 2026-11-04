@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoQuality
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
        val sut = rig.engine()
        try {
            // The scripted camera's frames are 64 x 48.
            steps.open(sut)
            steps.at(1_000)
            val path = steps.startRecording(sut, steps.video().copy(quality = VideoQuality.FHD))
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

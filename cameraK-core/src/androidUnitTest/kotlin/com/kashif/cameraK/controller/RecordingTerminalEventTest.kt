@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent.Finalize
import com.kashif.cameraK.engine.FakeAndroidPlatform
import com.kashif.cameraK.engine.open
import com.kashif.cameraK.engine.startRecording
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * CameraX ends a recording with a Finalize event, and not only when asked to: the camera can be
 * taken away (source inactive), the disk can fill, a size or duration limit can be reached. Every
 * Finalize is the end of the recording, and the photo session comes back without VideoCapture. The
 * outcome rules are RecordingOutcomeTest's and FinalizedContractTest's.
 */
@RunWith(RobolectricTestRunner::class)
class RecordingTerminalEventTest {
    private val platform = FakeAndroidPlatform()

    @Test
    fun recordingEndedByCameraX_restoresThePhotoSessionWithoutVideoCapture() = runTest {
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        val (recording, _) = startRecording(platform, engine, VideoConfiguration { enableAudio = false })

        // The host went to the background: CameraX finalizes without anyone calling stop.
        recording.finalize(Finalize.ERROR_SOURCE_INACTIVE)
        platform.settle(testScheduler)

        assertEquals(
            emptyList(),
            platform.backend.binding.bound.filterIsInstance<VideoCapture<*>>(),
            "after CameraX ended the recording, stills are still cropped by a bound VideoCapture",
        )
        engine.closeAndJoin()
    }
}

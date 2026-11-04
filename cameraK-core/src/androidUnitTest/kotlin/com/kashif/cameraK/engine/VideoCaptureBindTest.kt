@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.video.VideoCapture
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CameraX refuses a group whose streams the camera cannot run together with IllegalArgumentException.
 * Adding VideoCapture is then `Unsupported(VIDEO_QUALITY)`, and the bind rolls back to the photo
 * session without it, with the camera still open (§4.1).
 */
@RunWith(RobolectricTestRunner::class)
class VideoCaptureBindTest {
    private val platform = FakeAndroidPlatform()

    @Test
    fun illegalArgumentOnBindRollsBackAndIsUnsupportedVideoQuality() = runTest {
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        platform.backend.binding.refuseVideoCapture = true

        val result = engine.startRecording(VideoConfiguration())
        platform.settle(testScheduler)

        val error = assertIs<CameraKError.Unsupported>(assertIs<CameraKResult.Failure>(result).error)
        assertEquals(CameraFeature.VIDEO_QUALITY, error.feature)
        val bound = platform.backend.binding.bound
        assertEquals(emptyList(), bound.filterIsInstance<VideoCapture<*>>(), "VideoCapture stayed bound")
        assertTrue(bound.any { it is Preview } && bound.any { it is ImageCapture }, "the photo session is gone: $bound")
        assertTrue(platform.backend.recordings.isEmpty(), "a recording started on a refused bind")
        assertEquals(EngineState.Open, engine.state.value, "the refused bind ended the session")
        engine.closeAndJoin()
    }
}

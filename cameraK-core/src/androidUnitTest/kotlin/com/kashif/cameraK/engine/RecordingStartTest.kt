@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent.Finalize
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CameraX can finalize a recording before it ever reports Start, when the encoder or the output fails
 * at once. That recording never started: startRecording fails, nothing reaches recordingEvents, and
 * the VideoCapture bound for it is unbound again so stills are full-FOV (#136).
 */
@RunWith(RobolectricTestRunner::class)
class RecordingStartTest {
    private val platform = FakeAndroidPlatform()

    @Test
    fun finalizeBeforeStartFailsStartRecording() = runTest {
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        val events = recordingEventsOf(engine)
        platform.backend.holdRecordingStart = true

        val start = async { engine.startRecording(VideoConfiguration()) }
        platform.settle(testScheduler)
        val recording = platform.backend.recordings.single()
        assertFalse(start.isCompleted, "setup: startRecording waits for CameraX's Start")
        recording.finalize(Finalize.ERROR_ENCODING_FAILED, written = null)
        platform.settle(testScheduler)

        assertTrue(start.isCompleted, "startRecording still waits after CameraX finalized the recording")
        assertIs<CameraKResult.Failure>(start.await(), "a recording finalized before its Start")
        assertTrue(events.isEmpty(), "a recording that never started reached the events: $events")
        assertEquals(
            emptyList(),
            platform.backend.binding.bound.filterIsInstance<VideoCapture<*>>(),
            "the failed start left VideoCapture bound",
        )
        assertFalse(recording.outputFile.exists(), "the failed start left its file")
        engine.closeAndJoin()
    }
}

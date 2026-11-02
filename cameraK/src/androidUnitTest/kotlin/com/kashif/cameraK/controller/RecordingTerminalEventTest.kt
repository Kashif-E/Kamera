package com.kashif.cameraK.controller

import android.content.Context
import android.os.Looper
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent.Finalize
import androidx.camera.view.PreviewView
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.video.VideoCaptureResult
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CameraX ends a recording with a Finalize event, and not only when asked to: the camera can be
 * taken away (source inactive), the disk can fill, a size or duration limit can be reached. Every
 * Finalize is the end of the recording, and several of them leave a playable file behind.
 */
@RunWith(RobolectricTestRunner::class)
class RecordingTerminalEventTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val hostScope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        hostScope.cancel()
    }

    @Test
    fun recordingEndedByCameraX_restoresThePhotoSessionWithoutVideoCapture() {
        val camera = RecordingCamera()
        val recording = camera.startRecording()

        // The host went to the background: CameraX finalizes without anyone calling stop.
        recording.finalize(Finalize.ERROR_SOURCE_INACTIVE)
        idleMainLooper()

        assertEquals(
            emptyList(),
            camera.backend.binding.bound.filterIsInstance<VideoCapture<*>>(),
            "after CameraX ended the recording, stills are still cropped by a bound VideoCapture",
        )
    }

    @Test
    fun stopAfterCameraXEndedTheRecording_returnsThatRecordingsFile() {
        val camera = RecordingCamera()
        val recording = camera.startRecording()
        recording.finalize(Finalize.ERROR_SOURCE_INACTIVE)
        idleMainLooper()

        val result = camera.stop()

        val success = assertIs<VideoCaptureResult.Success>(result, "the recording's outcome was lost")
        assertEquals(recording.outputFile.absolutePath, success.filePath)
    }

    @Test
    fun finalizeErrorsThatLeaveAPlayableFile_areReportedAsTheRecordedFile() {
        for (error in listOf(
            Finalize.ERROR_SOURCE_INACTIVE,
            Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
            Finalize.ERROR_DURATION_LIMIT_REACHED,
            Finalize.ERROR_INSUFFICIENT_STORAGE,
        )) {
            val camera = RecordingCamera()
            val recording = camera.startRecording()

            val result = camera.stop { recording.finalize(error, recordedDurationNanos = 2_000_000_000L) }

            val success = assertIs<VideoCaptureResult.Success>(result, "error $error discarded a playable file")
            assertEquals(recording.outputFile.absolutePath, success.filePath, "error $error")
            assertEquals(2_000L, success.durationMs, "error $error")
            camera.controller.cleanup()
        }
    }

    @Test
    fun finalizeErrorWithoutAPlayableFile_isAnError() {
        val camera = RecordingCamera()
        val recording = camera.startRecording()

        val result = camera.stop { recording.finalize(Finalize.ERROR_ENCODING_FAILED, written = null) }

        assertIs<VideoCaptureResult.Error>(result)
    }

    private inner class RecordingCamera {
        val backend = FakeCameraXBackend()
        val controller = testController(context, backend)

        init {
            controller.bindCamera(PreviewView(context))
            idleMainLooper()
        }

        fun startRecording(): FakeRecording {
            val start = hostScope.async(start = CoroutineStart.UNDISPATCHED) {
                controller.startRecording(VideoConfiguration(enableAudio = false))
            }
            idleMainLooper()
            assertTrue(start.isCompleted, "setup: the recording did not start")
            runBlocking { start.await() }
            return backend.recordings.single()
        }

        /** Stops the recording; [finalize] is how CameraX then ends it. */
        fun stop(finalize: () -> Unit = {}): VideoCaptureResult {
            val stop: Deferred<VideoCaptureResult> =
                hostScope.async(start = CoroutineStart.UNDISPATCHED) { controller.stopRecording() }
            finalize()
            idleMainLooper()
            assertTrue(stop.isCompleted, "stopRecording is still waiting")
            return runBlocking { stop.await() }
        }
    }
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

package com.kashif.cameraK.controller

import android.content.Context
import android.os.Looper
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.video.VideoCaptureResult
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A long recording at high resolution can fill the device. The size and duration limits in the
 * configuration go to CameraX's Recorder, which stops at the limit and finalizes a playable file.
 */
@RunWith(RobolectricTestRunner::class)
class RecordingLimitsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val backend = FakeCameraXBackend()
    private val controller = testController(context, backend)
    private val hostScope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        hostScope.cancel()
    }

    @Test
    fun sizeAndDurationLimits_reachTheRecorder() {
        start(
            VideoConfiguration {
                enableAudio = false
                maxDuration = 30.seconds
                maxFileSizeBytes = 50_000_000L
            },
        )

        val output = backend.recordings.single().output
        assertEquals(30_000L, output.durationLimitMillis, "the duration limit never reached CameraX")
        assertEquals(50_000_000L, output.fileSizeLimit, "the file size limit never reached CameraX")
    }

    @Test
    fun noLimits_recordWithoutLimits() {
        start(VideoConfiguration { enableAudio = false })

        val output = backend.recordings.single().output
        assertEquals(0L, output.durationLimitMillis)
        assertEquals(0L, output.fileSizeLimit)
    }

    @Test
    fun subMillisecondDurationLimit_isOneMillisecond() {
        start(
            VideoConfiguration {
                enableAudio = false
                maxDuration = 500.microseconds
            },
        )

        // CameraX reads 0 as no limit, so a positive limit under a millisecond must not round down to it.
        assertEquals(1L, backend.recordings.single().output.durationLimitMillis, "the limit became unlimited")
    }

    @Test
    fun recordingStoppedAtItsSizeLimit_keepsItsFile() {
        start(
            VideoConfiguration {
                enableAudio = false
                maxFileSizeBytes = 4L
            },
        )
        backend.recordings.single().finalize(error = VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED)
        idleMainLooper()

        val result = hostScope.async(start = CoroutineStart.UNDISPATCHED) { controller.stopRecording() }
        idleMainLooper()
        assertTrue(result.isCompleted, "stopRecording did not return")

        val success = assertIs<VideoCaptureResult.Success>(runBlocking { result.await() })
        assertEquals(backend.recordings.single().outputFile.absolutePath, success.filePath)
    }

    private fun start(configuration: VideoConfiguration) {
        controller.bindCamera(PreviewView(context))
        idleMainLooper()
        val started = hostScope.async(start = CoroutineStart.UNDISPATCHED) { controller.startRecording(configuration) }
        idleMainLooper()
        assertTrue(started.isCompleted, "setup: startRecording did not return")
        runBlocking { started.await() }
    }
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

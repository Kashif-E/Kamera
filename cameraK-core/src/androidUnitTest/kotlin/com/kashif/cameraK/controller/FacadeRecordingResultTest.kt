package com.kashif.cameraK.controller

import android.content.Context
import android.os.Looper
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The 1.x facade reports the engine's recording outcome (w6 G4): a file is a recording only when the
 * playable probe reads a duration from it, so a file CameraX left but no player can open is an error,
 * not a `VideoCaptureResult.Success`.
 */
@RunWith(RobolectricTestRunner::class)
class FacadeRecordingResultTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val hostScope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val backend = FakeCameraXBackend()
    private val controller = testController(context, backend)

    @After
    fun tearDown() {
        hostScope.cancel()
        controller.cleanup()
        idleMainLooper()
    }

    @Test
    fun anUnplayableFileIsNotReportedAsARecording() {
        controller.bindCamera(PreviewView(context))
        idleMainLooper()
        val start = hostScope.async(start = CoroutineStart.UNDISPATCHED) {
            controller.startRecording(VideoConfiguration())
        }
        idleMainLooper()
        assertTrue(start.isCompleted, "setup: the recording did not start")
        runBlocking { start.await() }

        val stop = hostScope.async(start = CoroutineStart.UNDISPATCHED) { controller.stopRecording() }
        // CameraX ends the recording normally, but what it wrote has no readable duration.
        backend.recordings.single().finalize(written = byteArrayOf(0, 0, 0, 24), playable = false)
        idleMainLooper()

        assertTrue(stop.isCompleted, "stopRecording did not return")
        assertIs<VideoCaptureResult.Error>(
            runBlocking {
                stop.await()
            },
            "an unplayable file was reported as a recording",
        )
    }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()
}

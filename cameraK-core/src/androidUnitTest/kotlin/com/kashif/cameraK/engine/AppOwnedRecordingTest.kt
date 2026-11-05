@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.os.Looper
import androidx.camera.view.PreviewView
import com.kashif.cameraK.controller.FakeCameraXBackend
import com.kashif.cameraK.controller.testController
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.utils.CameraKEngineApi
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
import org.robolectric.shadows.ShadowMediaScannerConnection
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Recordings are app-owned (Decision 10, w6 G3): the facade's PICTURES no longer sends a recording to
 * public Movies, and core never hands a recording to the media scanner (#158, which scanned private
 * recordings too). The engine's default folder is DefaultOutputDirectoryTest's. Publishing is the media saver plugin's job.
 */
@RunWith(RobolectricTestRunner::class)
class AppOwnedRecordingTest {
    private val hostScope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After
    fun tearDown() = hostScope.cancel()

    @Test
    fun noRecordingIsEverPassedToTheMediaScanner() {
        val backend = FakeCameraXBackend()
        val controller = testController(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            backend,
            directory = Directory.PICTURES,
        )
        controller.bindCamera(PreviewView(controller.context))
        idleMainLooper()
        val start = hostScope.async(start = CoroutineStart.UNDISPATCHED) {
            controller.startRecording(VideoConfiguration())
        }
        idleMainLooper()
        assertTrue(start.isCompleted, "setup: the recording did not start")
        val path = runBlocking { start.await() }
        assertTrue(
            File(path).canonicalPath.startsWith(controller.context.filesDir.canonicalPath + File.separator),
            "the facade's PICTURES recording is not app-owned: $path",
        )

        val stop = hostScope.async(start = CoroutineStart.UNDISPATCHED) { controller.stopRecording() }
        backend.recordings.single().finalize()
        idleMainLooper()
        assertIs<VideoCaptureResult.Success>(runBlocking { stop.await() }, "setup: the recording")

        assertEquals(emptySet(), ShadowMediaScannerConnection.getSavedPaths(), "a recording was sent to the scanner")
        controller.cleanup()
        idleMainLooper()
    }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()
}

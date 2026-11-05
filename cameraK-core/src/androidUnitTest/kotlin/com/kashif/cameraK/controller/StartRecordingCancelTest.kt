package com.kashif.cameraK.controller

import android.content.Context
import android.os.Looper
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * startRecording binds VideoCapture before it records, and VideoCapture in the session crops every
 * still (#136). A start that is cancelled while it waits for that bind never records, so the photo
 * session must come back without VideoCapture.
 */
@RunWith(RobolectricTestRunner::class)
class StartRecordingCancelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val backend = FakeCameraXBackend()
    private val controller = testController(context, backend)
    private val previewView = PreviewView(context)
    private val hostScope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        hostScope.cancel()
    }

    @Test
    fun startCancelledWhileWaitingForTheVideoBind_leavesTheSessionWithoutVideoCapture() {
        controller.bindCamera(previewView)
        idleMainLooper()

        val start = hostScope.launch(start = CoroutineStart.UNDISPATCHED) {
            controller.startRecording(VideoConfiguration { enableAudio = false })
        }
        // The VideoCapture bind is queued on the main thread; the caller gives up before it runs.
        assertTrue(start.isActive, "setup: startRecording should be waiting for the bind")
        start.cancel()
        idleMainLooper()

        assertEquals(
            emptyList(),
            backend.binding.bound.filterIsInstance<VideoCapture<*>>(),
            "a cancelled start left VideoCapture bound",
        )

        // Every later bind (lens switch, rotation) must also come back without VideoCapture.
        controller.toggleCameraLens()
        idleMainLooper()

        assertEquals(
            emptyList(),
            backend.binding.bound.filterIsInstance<VideoCapture<*>>(),
            "a cancelled start keeps adding VideoCapture to later binds",
        )
    }
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

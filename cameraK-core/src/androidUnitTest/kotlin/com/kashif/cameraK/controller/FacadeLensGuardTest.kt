package com.kashif.cameraK.controller

import android.content.Context
import android.os.Looper
import androidx.camera.view.PreviewView
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Characterization: the 1.x facade's toggleCameraLens and setPreferredCameraDeviceType return Unit,
 * so they cannot carry Busy; during a recording they log and ignore the switch, checking before they
 * change their own lens field, which therefore never drifts from the bound camera. C1' deletes them.
 */
@RunWith(RobolectricTestRunner::class)
class FacadeLensGuardTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val hostScope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val backend = FakeCameraXBackend()
    private val controller = testController(context, backend)

    @After
    fun tearDown() {
        hostScope.cancel()
    }

    @Test
    fun toggleDuringRecordingKeepsTheLensField() {
        controller.bindCamera(PreviewView(context))
        idleMainLooper()
        val start = hostScope.async(start = CoroutineStart.UNDISPATCHED) {
            controller.startRecording(VideoConfiguration(enableAudio = false))
        }
        idleMainLooper()
        assertTrue(start.isCompleted, "setup: the recording did not start")
        val binds = backend.binding.bindCalls

        controller.toggleCameraLens()
        controller.setPreferredCameraDeviceType(CameraDeviceType.TELEPHOTO)
        idleMainLooper()

        assertEquals(CameraLens.BACK, controller.getCameraLens(), "the lens field moved while the camera did not")
        assertEquals(CameraDeviceType.DEFAULT, controller.getPreferredCameraDeviceType())
        assertEquals(binds, backend.binding.bindCalls, "a switch during a recording rebound the camera")
    }
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

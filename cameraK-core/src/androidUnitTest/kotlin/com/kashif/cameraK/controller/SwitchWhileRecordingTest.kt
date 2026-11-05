package com.kashif.cameraK.controller

import android.content.Context
import android.os.Looper
import androidx.camera.view.PreviewView
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A lens switch or a device-type change rebinds, which would tear down a live recording, so during a
 * recording both are ignored and reported. Core logs under one tag, `CameraK`.
 */
@RunWith(RobolectricTestRunner::class)
class SwitchWhileRecordingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val hostScope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val backend = FakeCameraXBackend()
    private val controller = testController(context, backend)
    private val warnings = mutableListOf<Pair<String, String>>()

    @Before
    fun recording() {
        controller.bindCamera(PreviewView(context))
        idleMainLooper()
        val start = hostScope.async(start = CoroutineStart.UNDISPATCHED) {
            controller.startRecording(VideoConfiguration { enableAudio = false })
        }
        idleMainLooper()
        assertTrue(start.isCompleted, "setup: the recording did not start")
        runBlocking { start.await() }
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, tag, message, _ ->
            if (level == CameraKLogger.Level.WARN) warnings += tag to message
        }
    }

    @After
    fun tearDown() {
        hostScope.cancel()
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun lensSwitchAndDeviceTypeChange_duringARecording_doNotRebind() {
        val binds = backend.binding.bindCalls

        controller.toggleCameraLens()
        controller.setPreferredCameraDeviceType(CameraDeviceType.TELEPHOTO)
        idleMainLooper()

        assertEquals(binds, backend.binding.bindCalls, "a switch during a recording rebound the camera")
        assertEquals(CameraLens.BACK, controller.getCameraLens())
        assertEquals(CameraDeviceType.DEFAULT, controller.getPreferredCameraDeviceType())
        assertEquals(2, warnings.size, "each ignored switch is reported once: $warnings")
    }

    @Test
    fun ignoredSwitches_areReportedUnderTheCameraKTag() {
        controller.toggleCameraLens()
        controller.setPreferredCameraDeviceType(CameraDeviceType.TELEPHOTO)

        assertEquals(listOf("CameraK", "CameraK"), warnings.map { it.first }, "warnings: $warnings")
    }
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

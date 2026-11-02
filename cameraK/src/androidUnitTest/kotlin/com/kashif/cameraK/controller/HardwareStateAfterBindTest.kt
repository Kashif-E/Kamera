package com.kashif.cameraK.controller

import android.content.Context
import android.os.Looper
import androidx.camera.view.PreviewView
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.utils.CameraKLogger
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every bind gives CameraX a camera with the torch off and no zoom. Torch and zoom are what the user
 * (or the configuration) asked for, so they are applied to each camera a bind returns: the first one,
 * and every one after a lens switch, a device-type change or a recording start or stop.
 */
@RunWith(RobolectricTestRunner::class)
class HardwareStateAfterBindTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val backend = FakeCameraXBackend()
    private val previewView = PreviewView(context)
    private val problems = mutableListOf<String>()

    @After
    fun tearDown() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun torchOnInTheConfiguration_isTurnedOnWhenTheCameraIsBound() {
        val controller = testController(context, backend, torchMode = TorchMode.ON)

        controller.bindCamera(previewView)
        idleMainLooper()

        assertEquals(
            listOf(true),
            backend.binding.cameras.single().torchRequests,
            "the configured torch was never applied",
        )
    }

    @Test
    fun torchAndZoomSetByTheUser_areAppliedAgainAfterARebind() {
        val controller = testController(context, backend)
        controller.bindCamera(previewView)
        idleMainLooper()
        controller.setTorchMode(TorchMode.ON)
        controller.setZoom(2.5f)

        controller.toggleCameraLens()
        idleMainLooper()

        val rebound = backend.binding.cameras.last()
        assertEquals(2, backend.binding.cameras.size, "setup: the lens switch should rebind")
        assertEquals(listOf(true), rebound.torchRequests, "the torch went dark after the rebind")
        assertEquals(listOf(2.5f), rebound.zoomRequests, "the zoom fell back to 1x after the rebind")
    }

    @Test
    fun torchOnACameraWithoutAFlashUnit_isNotRequestedAndIsReported() {
        backend.binding.newCamera = { FakeCamera(hasFlashUnit = false) }
        val controller = testController(context, backend)
        controller.bindCamera(previewView)
        idleMainLooper()
        recordProblems()

        controller.setTorchMode(TorchMode.ON)
        controller.toggleTorchMode()
        idleMainLooper()

        assertEquals(
            emptyList(),
            backend.binding.cameras.single().torchRequests,
            "the torch was requested from a camera with no flash unit",
        )
        assertTrue(problems.any { "flash unit" in it }, "no report that the camera has no flash unit: $problems")
    }

    @Test
    fun torchRequestThatCameraXFails_isReported() {
        backend.binding.newCamera = { FakeCamera(torchFailure = IllegalStateException("Camera is closed")) }
        val controller = testController(context, backend)
        controller.bindCamera(previewView)
        idleMainLooper()
        recordProblems()

        controller.setTorchMode(TorchMode.ON)
        idleMainLooper()
        controller.toggleTorchMode()
        idleMainLooper()

        val failures = problems.filter { "torch" in it.lowercase() && "failed" in it }
        assertEquals(2, failures.size, "a failed torch request was dropped: $problems")
    }

    @Test
    fun zoomBelowOneX_reachesACameraThatGoesThatWide() {
        backend.binding.newCamera = { FakeCamera(minZoom = 0.6f) }
        val controller = testController(context, backend)
        controller.bindCamera(previewView)
        idleMainLooper()

        controller.setZoom(0.6f)
        controller.setZoom(0.2f)

        assertEquals(
            listOf(0.6f, 0.6f),
            backend.binding.cameras.single().zoomRequests,
            "the zoom was clamped to 1x on a camera that zooms out to 0.6x",
        )
    }

    private fun recordProblems() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, _ -> if (level != CameraKLogger.Level.DEBUG) problems += message }
    }
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

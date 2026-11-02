package com.kashif.cameraK.controller

import android.content.Context
import android.os.Looper
import androidx.camera.view.PreviewView
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.enums.TorchMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals

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
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

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
 * The 1.x facade holds one desired flash, torch and zoom (w4-design-decisions G3). A torch or zoom set
 * before startSession is kept and applied once the engine's camera is open, as 1.x applied it.
 */
@RunWith(RobolectricTestRunner::class)
class FacadeDesiredHardwareTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val backend = FakeCameraXBackend()

    @Test
    fun torchAndZoomSetBeforeStartAreAppliedAtStart() {
        val controller = testController(context, backend, started = false)
        controller.setTorchMode(TorchMode.ON)
        controller.setZoom(2f)
        idleMainLooper()
        assertEquals(0, backend.binding.bindCalls, "setup: nothing is bound before startSession")
        assertEquals(TorchMode.ON, controller.getTorchMode(), "the facade did not keep the torch set before start")

        controller.startSession()
        controller.bindCamera(PreviewView(context))
        idleMainLooper()

        val camera = backend.binding.cameras.single()
        assertEquals(listOf(true), camera.torchRequests, "the torch set before start never reached the open camera")
        assertEquals(listOf(2f), camera.zoomRequests, "the zoom set before start never reached the open camera")
    }
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

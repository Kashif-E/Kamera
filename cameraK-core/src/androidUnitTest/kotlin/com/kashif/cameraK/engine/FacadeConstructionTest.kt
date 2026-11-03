@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.content.Context
import android.os.Looper
import androidx.camera.view.PreviewView
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.controller.FakeCameraXBackend
import com.kashif.cameraK.controller.ResumedLifecycleOwner
import com.kashif.cameraK.controller.testController
import com.kashif.cameraK.utils.CameraKEngineApi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals

/**
 * A 1.x controller built directly, over the host's LifecycleOwner, builds and owns its own engine:
 * nothing binds before startSession starts that engine, and cleanup closes it, releasing the camera
 * once.
 */
@RunWith(RobolectricTestRunner::class)
class FacadeConstructionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val backend = FakeCameraXBackend()

    @Test
    fun directlyConstructedControllerOwnsItsEngine() {
        val controller = testController(context, backend, lifecycleOwner = ResumedLifecycleOwner(), started = false)
        controller.bindCamera(PreviewView(context))
        idleMainLooper()
        assertEquals(0, backend.binding.bindCalls, "the facade bound the camera before its engine started")

        controller.startSession()
        idleMainLooper()
        assertEquals(1, backend.binding.bindCalls, "startSession did not open the camera through the engine")
        assertEquals(EngineState.Open, controller.engine.state.value)

        controller.cleanup()
        controller.cleanup()
        idleMainLooper()
        assertEquals(1, backend.binding.releaseCount, "native releases after two cleanups")
        assertEquals(EngineState.Closed, controller.engine.state.value)
    }
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

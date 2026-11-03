@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import android.content.Context
import android.os.Looper
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.utils.CameraKEngineApi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A controller built directly follows its host, as the 1.x camera did: the host's ON_STOP stops the
 * engine, its next ON_START starts it again, and its ON_DESTROY runs cleanup().
 */
@RunWith(RobolectricTestRunner::class)
class HostLifecycleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val backend = FakeCameraXBackend()
    private val host = HostOwner()
    private val controller = testController(context, backend, lifecycleOwner = host)

    @Test
    fun hostStopStopsTheEngineAndTheNextStartReopensIt() {
        controller.bindCamera(PreviewView(context))
        idleMainLooper()
        assertEquals(EngineState.Open, controller.engine.state.value, "setup: the session is open")

        host.registry.currentState = Lifecycle.State.CREATED
        idleMainLooper()
        assertEquals(EngineState.Closed, controller.engine.state.value, "the host's ON_STOP did not stop the engine")

        host.registry.currentState = Lifecycle.State.RESUMED
        idleMainLooper()
        assertEquals(EngineState.Open, controller.engine.state.value, "the host's ON_START did not start the engine")
    }

    @Test
    fun hostDestroyRunsCleanup() {
        controller.bindCamera(PreviewView(context))
        idleMainLooper()

        host.registry.currentState = Lifecycle.State.DESTROYED
        idleMainLooper()

        assertTrue(controller.engine.isClosed, "the host's ON_DESTROY did not run cleanup()")
        assertEquals(EngineState.Closed, controller.engine.state.value)
        assertEquals(1, backend.binding.releaseCount, "native releases after the host was destroyed")
    }

    private class HostOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

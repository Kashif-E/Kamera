package com.kashif.cameraK.controller

import android.content.Context
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ProcessCameraProvider is one per process. Whatever else the host app has bound to it (its own
 * scanner, a second CameraK screen) belongs to someone else, and nothing this controller does may
 * unbind it.
 */
@RunWith(RobolectricTestRunner::class)
class OwnUseCasesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val backend = FakeCameraXBackend()
    private val controller = testController(context, backend)
    private val previewView = PreviewView(context)

    // Another CameraX consumer in the same process, bound before this controller opens the camera.
    private val hostPreview = Preview.Builder().build().also {
        backend.binding.bindToLifecycle(ResumedLifecycleOwner(), CameraSelector.DEFAULT_BACK_CAMERA, it)
    }

    @Test
    fun bindAndRebind_leaveAnotherOwnersUseCasesBound() {
        controller.bindCamera(previewView)
        idleMainLooper()
        assertTrue(hostPreview in backend.binding.bound, "binding the camera unbound the host's own preview")

        // A lens switch rebinds this controller's use cases.
        controller.toggleCameraLens()
        idleMainLooper()

        assertTrue(hostPreview in backend.binding.bound, "rebinding the camera unbound the host's own preview")
    }

    @Test
    fun stopSession_unbindsOnlyThisControllersUseCases() {
        controller.bindCamera(previewView)
        idleMainLooper()

        controller.stopSession()

        assertEquals(
            setOf(hostPreview),
            backend.binding.bound.toSet(),
            "stopSession must unbind exactly its own use cases",
        )
    }
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

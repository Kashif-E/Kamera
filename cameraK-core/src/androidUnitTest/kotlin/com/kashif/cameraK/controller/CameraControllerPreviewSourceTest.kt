@file:OptIn(CameraKEngineApi::class, InternalCameraKApi::class)

package com.kashif.cameraK.controller

import android.content.Context
import android.os.Looper
import androidx.camera.core.Preview
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.InternalCameraKApi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The 1.x CameraPreviewView renders through the same viewfinder as CameraPreview(holder), so it needs
 * the facade's engine preview rather than a PreviewView of its own (design §12.3 UA).
 */
@RunWith(RobolectricTestRunner::class)
class CameraControllerPreviewSourceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val backend = FakeCameraXBackend()

    @Test
    fun previewSourceIsTheEnginesPreview() {
        val controller = testController(context, backend)

        assertSame(
            controller.engine.preview,
            controller.previewSource,
            "the facade's previewSource is not the engine's",
        )

        val provider = Preview.SurfaceProvider { }
        val attachment = controller.previewSource.attach(provider, display = null)
        idleMainLooper()
        val preview = backend.binding.bound.filterIsInstance<Preview>().single()
        assertSame(provider, preview.surfaceProvider, "a provider attached through previewSource is not the Preview's")

        attachment.close()
        idleMainLooper()
        assertNull(preview.surfaceProvider, "closing the attachment left its provider on the Preview")
    }
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

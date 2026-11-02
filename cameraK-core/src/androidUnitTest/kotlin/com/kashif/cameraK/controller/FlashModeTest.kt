package com.kashif.cameraK.controller

import android.content.Context
import android.os.Looper
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.enums.FlashMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The flash mode is a setting of the bound ImageCapture, kept across binds. */
@RunWith(RobolectricTestRunner::class)
class FlashModeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val backend = FakeCameraXBackend()

    @Test
    fun toggleFlashMode_cyclesOffOnAutoAndReachesTheBoundCapture() {
        val controller = testController(context, backend)
        controller.bindCamera(PreviewView(context))
        idleMainLooper()

        val seen = (1..3).map {
            controller.toggleFlashMode()
            controller.getFlashMode() to boundCapture().flashMode
        }

        assertEquals(
            listOf(
                FlashMode.ON to ImageCapture.FLASH_MODE_ON,
                FlashMode.AUTO to ImageCapture.FLASH_MODE_AUTO,
                FlashMode.OFF to ImageCapture.FLASH_MODE_OFF,
            ),
            seen,
        )
    }

    @Test
    fun flashModeSetBeforeTheBind_isTheBoundCapturesModeAndSurvivesALensSwitch() {
        val controller = testController(context, backend, flashMode = FlashMode.OFF)
        controller.setFlashMode(FlashMode.AUTO)
        assertNull(controller.getFlashMode(), "before a bind there is no capture to report a flash mode")

        controller.bindCamera(PreviewView(context))
        idleMainLooper()
        assertEquals(ImageCapture.FLASH_MODE_AUTO, boundCapture().flashMode)

        controller.toggleCameraLens()
        idleMainLooper()
        assertEquals(ImageCapture.FLASH_MODE_AUTO, boundCapture().flashMode, "the lens switch lost the flash mode")
        assertEquals(FlashMode.AUTO, controller.getFlashMode())
    }

    private fun boundCapture(): ImageCapture = backend.binding.bound.filterIsInstance<ImageCapture>().single()
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

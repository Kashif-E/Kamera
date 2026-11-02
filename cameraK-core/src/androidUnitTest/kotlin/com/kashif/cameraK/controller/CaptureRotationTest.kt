package com.kashif.cameraK.controller

import android.os.Looper
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import com.kashif.cameraK.enums.DeviceOrientation
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals

/**
 * Captures follow the display rotation unless the app sets a target orientation, which overrides it
 * until it is cleared.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureRotationTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val backend = FakeCameraXBackend()
    private val controller = testController(activity, backend, lifecycleOwner = activity)

    @Test
    fun targetOrientation_overridesTheDisplayRotationUntilCleared() {
        controller.bindCamera(PreviewView(activity))
        idleMainLooper()
        assertEquals(Surface.ROTATION_0, boundCapture().targetRotation, "setup: a portrait display")

        controller.setTargetOrientation(DeviceOrientation.LANDSCAPE_LEFT)
        assertEquals(Surface.ROTATION_90, boundCapture().targetRotation)

        controller.setTargetOrientation(DeviceOrientation.PORTRAIT_UPSIDE_DOWN)
        assertEquals(Surface.ROTATION_180, boundCapture().targetRotation)

        controller.setTargetOrientation(null)
        assertEquals(Surface.ROTATION_0, boundCapture().targetRotation, "clearing the override kept it")
    }

    @Test
    fun targetOrientationSetBeforeTheBind_isTheBoundCapturesRotation() {
        controller.setTargetOrientation(DeviceOrientation.LANDSCAPE_RIGHT)

        controller.bindCamera(PreviewView(activity))
        idleMainLooper()

        assertEquals(Surface.ROTATION_270, boundCapture().targetRotation)
    }

    private fun boundCapture(): ImageCapture = backend.binding.bound.filterIsInstance<ImageCapture>().single()
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

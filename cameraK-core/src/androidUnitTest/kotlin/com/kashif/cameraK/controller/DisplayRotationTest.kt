package com.kashif.cameraK.controller

import android.os.Looper
import android.util.Rational
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.camera.view.PreviewView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplay
import kotlin.test.assertEquals

/**
 * The capture crop (ViewPort) follows the display, so it has to be rebuilt when the display turns,
 * whether or not the accelerometer has reported anything. A host that handles orientation changes
 * itself is not recreated, so nothing else would rebuild it (#136).
 */
@RunWith(RobolectricTestRunner::class)
class DisplayRotationTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val backend = FakeCameraXBackend()
    private val controller = testController(activity, backend, lifecycleOwner = activity)

    @Test
    fun displayTurningLandscape_rebuildsTheViewPortForLandscape() {
        controller.bindCamera(PreviewView(activity))
        idleMainLooper()
        assertEquals(Rational(3, 4), backend.binding.viewPorts.single()?.aspectRatio, "setup: a portrait crop")

        shadowOf(ShadowDisplay.getDefaultDisplay()).setRotation(Surface.ROTATION_90)
        idleMainLooper()

        val viewPort = backend.binding.viewPorts.last()
        assertEquals(2, backend.binding.viewPorts.size, "the display turned but the camera kept its portrait crop")
        assertEquals(Rational(4, 3), viewPort?.aspectRatio)
        assertEquals(Surface.ROTATION_90, viewPort?.rotation)
    }
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

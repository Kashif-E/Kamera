@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import android.util.Rational
import android.view.Surface
import com.kashif.cameraK.engine.FakeAndroidPlatform
import com.kashif.cameraK.engine.open
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
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
    private val platform = FakeAndroidPlatform()

    @Test
    fun displayTurningLandscape_rebuildsTheViewPortForLandscape() = runTest {
        val engine = platform.engine(testScheduler, CameraConfiguration { aspectRatio = AspectRatio.RATIO_4_3 })
        open(platform, engine)
        val backend = platform.backend
        assertEquals(Rational(3, 4), backend.binding.viewPorts.single()?.aspectRatio, "setup: a portrait crop")

        shadowOf(ShadowDisplay.getDefaultDisplay()).setRotation(Surface.ROTATION_90)
        platform.settle(testScheduler)

        val viewPort = backend.binding.viewPorts.last()
        assertEquals(2, backend.binding.viewPorts.size, "the display turned but the camera kept its portrait crop")
        assertEquals(Rational(4, 3), viewPort?.aspectRatio)
        assertEquals(Surface.ROTATION_90, viewPort?.rotation)
        engine.closeAndJoin()
    }
}

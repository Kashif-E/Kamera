@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Rational
import android.view.Surface
import androidx.camera.core.Preview
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.desired
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplayManager
import kotlin.test.assertEquals

/**
 * The ViewPort follows the display the preview is attached on, so split-screen and external displays
 * crop to what is shown; with no preview attached it follows the default display.
 */
@RunWith(RobolectricTestRunner::class)
class ViewPortDisplayTest {
    private val platform = FakeAndroidPlatform()

    @Test
    fun usesAttachedDisplayElseDefaultDisplay() = runTest {
        val engine = platform.engine(testScheduler)
        assertEquals(CameraKResult.Success(Unit), engine.start(desired))
        platform.settle(testScheduler)
        val first = platform.backend.binding.viewPorts.last()!!
        assertEquals(Surface.ROTATION_0, first.rotation, "with no preview attached: the default display")
        assertEquals(Rational(9, 16), first.aspectRatio, "the configured 16:9, portrait on the default display")

        val displays = platform.context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val external = displays.getDisplay(ShadowDisplayManager.addDisplay("w640dp-h360dp"))
        shadowOf(external).setRotation(Surface.ROTATION_90)
        engine.preview.attach(Preview.SurfaceProvider { }, external)
        platform.settle(testScheduler)

        val attached = platform.backend.binding.viewPorts.last()!!
        assertEquals(Surface.ROTATION_90, attached.rotation, "the ViewPort ignored the display the preview is on")
        assertEquals(Rational(16, 9), attached.aspectRatio, "the crop did not turn with the attached display")
        engine.closeAndJoin()
    }
}

@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Rational
import android.view.Surface
import androidx.camera.core.Preview
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
 * Portrait or landscape is the display's, not its rotation's (PR #182): on a landscape-first panel
 * (tablets, Chromebooks, some multi-window layouts) ROTATION_0 is landscape, so the ViewPort and the
 * capture crop must be landscape there.
 */
@RunWith(RobolectricTestRunner::class)
class ViewPortNaturalOrientationTest {
    private val platform = FakeAndroidPlatform()

    @Test
    fun aLandscapeNaturalDisplayAtRotation0CropsTheCaptureAsLandscape() = runTest {
        val displays = platform.context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val tablet = displays.getDisplay(ShadowDisplayManager.addDisplay("w640dp-h400dp"))
        shadowOf(tablet).setRotation(Surface.ROTATION_0)
        val engine = platform.engine(testScheduler)
        engine.preview.attach(Preview.SurfaceProvider { }, tablet)
        platform.settle(testScheduler)

        assertEquals(CameraKResult.Success(Unit), engine.start(FakeAndroidPlatform.desired))
        platform.settle(testScheduler)

        val viewPort = platform.backend.binding.viewPorts.last()!!
        assertEquals(Surface.ROTATION_0, viewPort.rotation, "setup: the landscape panel's natural rotation")
        assertEquals(Rational(16, 9), viewPort.aspectRatio, "a landscape display at ROTATION_0 cropped as portrait")
        engine.closeAndJoin()
    }
}

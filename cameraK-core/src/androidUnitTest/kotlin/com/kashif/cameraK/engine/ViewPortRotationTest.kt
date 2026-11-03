@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.util.Rational
import android.view.Surface
import androidx.camera.core.ImageCapture
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.desired
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplay
import kotlin.test.assertEquals

/**
 * The display listener runs only between start and stop. A display that turns while the engine is
 * stopped leaves the ViewPort stale, and the next start rebinds when the orientation changed, even
 * when it opens the same camera, so the crop and the capture rotation match the display again.
 */
@RunWith(RobolectricTestRunner::class)
class ViewPortRotationTest {
    private val platform = FakeAndroidPlatform()
    private val success = CameraKResult.Success(Unit)

    @Test
    fun aDisplayTurnedWhileStoppedIsAppliedByTheNextStart() = runTest {
        val engine = platform.engine(testScheduler)
        assertEquals(success, engine.start(desired))
        platform.settle(testScheduler)
        val capture = boundCapture()
        assertEquals(Surface.ROTATION_0, capture.targetRotation, "setup: a portrait display")

        engine.stop()
        platform.settle(testScheduler)
        shadowOf(ShadowDisplay.getDefaultDisplay()).setRotation(Surface.ROTATION_90)
        platform.settle(testScheduler)
        assertEquals(1, platform.backend.binding.bindCalls, "a display change while stopped rebound the camera")
        assertEquals(
            Surface.ROTATION_0,
            capture.targetRotation,
            "the display listener ran while the engine was stopped",
        )

        assertEquals(success, engine.start(desired), "start after the display turned")
        platform.settle(testScheduler)
        assertEquals(2, platform.backend.binding.bindCalls, "the next start kept the stale portrait ViewPort")
        val viewPort = platform.backend.binding.viewPorts.last()!!
        assertEquals(Rational(16, 9), viewPort.aspectRatio, "the crop did not turn with the display")
        assertEquals(Surface.ROTATION_90, viewPort.rotation)
        assertEquals(
            Surface.ROTATION_90,
            boundCapture().targetRotation,
            "the capture rotation did not follow the display",
        )
        engine.closeAndJoin()
    }

    private fun boundCapture(): ImageCapture = platform.backend.binding.bound.filterIsInstance<ImageCapture>().single()
}

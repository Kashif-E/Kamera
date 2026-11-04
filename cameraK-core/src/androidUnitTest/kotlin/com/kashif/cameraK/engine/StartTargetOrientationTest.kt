@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.view.Surface
import androidx.camera.core.ImageCapture
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.desired
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * What a start binds as the target orientation (w6 A2 carry-over 3 and 4). A start that keeps the
 * bind still applies the capture rotation, because the target may have changed while the engine was
 * stopped (M9). A target stored while Closed is what the next start binds when the caller passes
 * none; a caller's own target wins over it (G2 through the 2.0 path).
 */
@RunWith(RobolectricTestRunner::class)
class StartTargetOrientationTest {
    private val platform = FakeAndroidPlatform()
    private val success = CameraKResult.Success(Unit)

    @Test
    fun aStartThatKeepsTheBindAppliesTheTargetItWasGiven() = runTest {
        val engine = platform.engine(testScheduler)
        assertEquals(success, engine.start(desiredWith(DeviceOrientation.LANDSCAPE_LEFT)))
        platform.settle(testScheduler)
        assertEquals(Surface.ROTATION_90, boundCapture().targetRotation, "setup: the first target")
        engine.stop()
        platform.settle(testScheduler)

        assertEquals(success, engine.start(desiredWith(DeviceOrientation.LANDSCAPE_RIGHT)))
        platform.settle(testScheduler)

        assertEquals(1, platform.backend.binding.bindCalls, "setup: the start kept the bind")
        assertEquals(Surface.ROTATION_270, boundCapture().targetRotation, "the kept capture kept the old target")
        assertEquals(DeviceOrientation.LANDSCAPE_RIGHT, engine.hardware.value.targetOrientation)
        engine.closeAndJoin()
    }

    @Test
    fun aTargetStoredWhileClosedIsBoundWhenTheStartPassesNone() = runTest {
        val engine = platform.engine(testScheduler)
        platform.settle(testScheduler)
        assertEquals(success, engine.setTargetOrientation(DeviceOrientation.LANDSCAPE_LEFT), "while Closed")

        assertEquals(success, engine.start(desiredWith(null)))
        platform.settle(testScheduler)

        assertEquals(Surface.ROTATION_90, boundCapture().targetRotation, "the start dropped the stored target")
        assertEquals(DeviceOrientation.LANDSCAPE_LEFT, engine.hardware.value.targetOrientation)
        engine.closeAndJoin()
    }

    @Test
    fun theCallersTargetWinsOverTheStoredOne() = runTest {
        val engine = platform.engine(testScheduler)
        platform.settle(testScheduler)
        assertEquals(success, engine.setTargetOrientation(DeviceOrientation.LANDSCAPE_LEFT), "while Closed")

        assertEquals(success, engine.start(desiredWith(DeviceOrientation.PORTRAIT_UPSIDE_DOWN)))
        platform.settle(testScheduler)

        assertEquals(Surface.ROTATION_180, boundCapture().targetRotation, "the stored target won over the caller's")
        assertEquals(DeviceOrientation.PORTRAIT_UPSIDE_DOWN, engine.hardware.value.targetOrientation)
        engine.closeAndJoin()
    }

    private fun desiredWith(target: DeviceOrientation?) = DesiredHardware(
        selection = desired.selection,
        flashMode = desired.flashMode,
        torchMode = desired.torchMode,
        zoomRatio = desired.zoomRatio,
        targetOrientation = target,
    )

    private fun boundCapture(): ImageCapture = platform.backend.binding.bound.filterIsInstance<ImageCapture>().single()
}

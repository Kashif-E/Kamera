@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.view.Surface
import androidx.camera.core.ImageCapture
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.FRONT
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.desired
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The target orientation is one value the engine owns (`engine.orientation`): setTargetOrientation
 * sets it, applies it to the bound outputs and, while Open, reports it in `hardware` before it
 * returns; null follows the display again. It is configuration, not a hardware command: before Open
 * it is stored and applied at the next bind (w4-design-decisions G2).
 */
@RunWith(RobolectricTestRunner::class)
class TargetOrientationTest {
    private val platform = FakeAndroidPlatform()
    private val success = CameraKResult.Success(Unit)

    @Test
    fun targetSetsTheCaptureRotationAndIsReported() = runTest {
        val engine = platform.engine(testScheduler)
        assertEquals(success, engine.start(desired))
        platform.settle(testScheduler)

        assertEquals(success, engine.setTargetOrientation(DeviceOrientation.LANDSCAPE_LEFT))

        assertEquals(
            DeviceOrientation.LANDSCAPE_LEFT,
            engine.hardware.value.targetOrientation,
            "when the command returns",
        )
        assertEquals(Surface.ROTATION_90, boundCapture().targetRotation)
        engine.closeAndJoin()
    }

    @Test
    fun nullTargetFollowsTheDisplayRotationAgain() = runTest {
        val engine = platform.engine(testScheduler)
        assertEquals(success, engine.start(desired))
        platform.settle(testScheduler)
        assertEquals(success, engine.setTargetOrientation(DeviceOrientation.LANDSCAPE_RIGHT))
        assertEquals(Surface.ROTATION_270, boundCapture().targetRotation, "setup: the target applies")

        assertEquals(success, engine.setTargetOrientation(null))

        assertEquals(null, engine.hardware.value.targetOrientation)
        assertEquals(Surface.ROTATION_0, boundCapture().targetRotation, "the capture kept the cleared target")
        engine.closeAndJoin()
    }

    @Test
    fun targetSurvivesARestartAndACameraSwitch() = runTest {
        val engine = platform.engine(testScheduler)
        assertEquals(success, engine.start(desired))
        platform.settle(testScheduler)
        assertEquals(success, engine.setTargetOrientation(DeviceOrientation.PORTRAIT_UPSIDE_DOWN))

        // The holder restarts with the desired hardware its successful commands updated.
        engine.stop()
        platform.settle(testScheduler)
        assertEquals(success, engine.start(desiredWith(DeviceOrientation.PORTRAIT_UPSIDE_DOWN)))
        platform.settle(testScheduler)
        assertEquals(
            DeviceOrientation.PORTRAIT_UPSIDE_DOWN,
            engine.hardware.value.targetOrientation,
            "after the restart",
        )
        assertEquals(Surface.ROTATION_180, boundCapture().targetRotation, "after the restart")

        assertIs<CameraKResult.Success<*>>(engine.selectCamera(CameraSelection.ById(FRONT.id)))
        platform.settle(testScheduler)
        assertEquals(
            DeviceOrientation.PORTRAIT_UPSIDE_DOWN,
            engine.hardware.value.targetOrientation,
            "after the switch",
        )
        assertEquals(Surface.ROTATION_180, boundCapture().targetRotation, "the new camera's capture")
        engine.closeAndJoin()
    }

    @Test
    fun beforeOpenIsStoredAndAppliedAtStart() = runTest {
        val engine = platform.engine(testScheduler)
        platform.settle(testScheduler)

        assertEquals(success, engine.setTargetOrientation(DeviceOrientation.LANDSCAPE_LEFT), "while Closed")
        assertEquals(EngineState.Closed, engine.state.value)
        assertEquals(0, platform.backend.binding.bindCalls, "a stored target opened the camera")

        platform.backend.binding.holdNextOpen = true
        val start = async { engine.start(desiredWith(DeviceOrientation.LANDSCAPE_LEFT)) }
        platform.settle(testScheduler)
        assertEquals(Surface.ROTATION_90, boundCapture().targetRotation, "the first bind")

        assertEquals(success, engine.setTargetOrientation(DeviceOrientation.PORTRAIT_UPSIDE_DOWN), "while Opening")
        assertEquals(Surface.ROTATION_180, boundCapture().targetRotation, "a target set while Opening")
        platform.reportOpen()
        platform.settle(testScheduler)
        assertEquals(success, start.await())
        assertEquals(DeviceOrientation.PORTRAIT_UPSIDE_DOWN, engine.hardware.value.targetOrientation, "once Open")
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

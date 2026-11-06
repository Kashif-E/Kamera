@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.FakeAndroidPlatform
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** With mirroring on, a front-camera photo is mirrored like the preview (#112); a back-camera one never is. */
@RunWith(RobolectricTestRunner::class)
class FrontMirrorTest {
    @Test
    fun mirroring_reversesFrontCapturesOnly() = runTest {
        assertEquals(true, captureIsReversed(mirror = true, front = true), "a mirrored front capture was not reversed")
        assertEquals(false, captureIsReversed(mirror = true, front = false), "a back capture was reversed")
        assertEquals(false, captureIsReversed(mirror = false, front = true), "an unmirrored front capture was reversed")
    }

    private suspend fun TestScope.captureIsReversed(mirror: Boolean, front: Boolean): Boolean {
        val platform = FakeAndroidPlatform()
        val engine = platform.engine(testScheduler, CameraConfiguration { mirrorFrontCamera = mirror })
        platform.settle(testScheduler)
        val lens = if (front) CameraLens.FRONT else CameraLens.BACK
        val desired = FakeAndroidPlatform.desired.let {
            DesiredHardware(
                CameraSelection.ByLens(lens, CameraDeviceType.DEFAULT),
                it.flashMode,
                it.torchMode,
                it.zoomRatio,
                it.targetOrientation,
            )
        }
        assertEquals(CameraKResult.Success(Unit), engine.start(desired), "start")
        platform.settle(testScheduler)
        assertEquals(EngineState.Open, engine.state.value)
        assertEquals(lens, engine.hardware.value.camera?.lens)

        val capture = backgroundScope.launch { engine.capture() }
        platform.settle(testScheduler)
        val reversed = platform.backend.captures.single().options.metadata.isReversedHorizontal
        capture.cancel()
        engine.closeAndJoin()
        return reversed
    }
}

@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.testing

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** What the fake platform reports, beyond what the contract kit checks. */
class FakeCameraEngineTest {
    private val success = CameraKResult.Success(Unit)
    private val reason = InterruptionReason.IN_USE_BY_ANOTHER_CLIENT

    private fun desiredOn(id: String) =
        DesiredHardware(CameraSelection.ById(id), FlashMode.OFF, TorchMode.OFF, 1f, targetOrientation = null)

    @Test
    fun reportsNoCameraBeforeTheFirstBindAndTheBoundCameraAfterIt() = runTest {
        val fake = FakeCameraEngine()
        assertNull(fake.hardware.value.camera, "a camera was reported before the first bind")

        assertEquals(success, fake.start(desiredOn(FakeCapabilities.FRONT_ID)))
        assertEquals(FakeCapabilities.FRONT_ID, fake.hardware.value.camera?.id, "after opening on the front camera")

        assertIs<CameraKResult.Success<LensInfo>>(fake.selectCamera(CameraSelection.ById(FakeCapabilities.BACK_ID)))
        assertEquals(FakeCapabilities.BACK_ID, fake.hardware.value.camera?.id, "after switching to the back camera")
        fake.close()
    }

    @Test
    fun interruptWhileClosedIsDroppedAndTheNextStartOpens() = runTest {
        val fake = FakeCameraEngine()

        fake.interrupt(reason)
        assertEquals(EngineState.Closed, fake.state.value, "an interruption before the first start")
        assertEquals(success, fake.start(desiredOn(FakeCapabilities.BACK_ID)))
        assertEquals(EngineState.Open, fake.state.value, "an interruption before the first start reached it")

        fake.stop()
        fake.interrupt(reason)
        assertEquals(EngineState.Closed, fake.state.value, "an interruption after stop")
        assertEquals(success, fake.start(desiredOn(FakeCapabilities.BACK_ID)))
        assertEquals(EngineState.Open, fake.state.value, "an interruption after stop reached the next generation")
        fake.close()
    }
}

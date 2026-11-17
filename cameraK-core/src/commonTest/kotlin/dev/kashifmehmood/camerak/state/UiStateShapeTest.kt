@file:OptIn(CameraKEngineApi::class, ExperimentalCoroutinesApi::class)

package dev.kashifmehmood.camerak.state

import dev.kashifmehmood.camerak.capture.CapturedImage
import dev.kashifmehmood.camerak.engine.CameraEngine
import dev.kashifmehmood.camerak.engine.HardwareState
import dev.kashifmehmood.camerak.enums.CameraLens
import dev.kashifmehmood.camerak.enums.DeviceOrientation
import dev.kashifmehmood.camerak.enums.FlashMode
import dev.kashifmehmood.camerak.enums.TorchMode
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.testing.FakeCameraEngine
import dev.kashifmehmood.camerak.testing.FakeCapabilities
import dev.kashifmehmood.camerak.testing.cameraKTestEnvironment
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `uiState`: every field derived from what the engine reports and the capture counter, and nothing
 * else writes it.
 */
class UiStateShapeTest {
    private fun TestScope.readyHolder(engine: CameraEngine, config: CameraConfiguration = CameraConfiguration()) =
        CameraKStateHolder(config, { _, _ -> engine }, emptyList(), cameraKTestEnvironment()).also {
            it.start()
            runCurrent()
            assertIs<CameraKState.Ready>(it.cameraState.value, "the holder did not reach Ready")
        }

    /** The fake with a hardware stream the test writes, for a target orientation the fake never reports. */
    private class ScriptedHardwareEngine(fake: FakeCameraEngine, initial: HardwareState) : CameraEngine by fake {
        val reported = MutableStateFlow(initial)
        override val hardware: StateFlow<HardwareState> get() = reported
    }

    @Test
    fun newFieldsComeFromHardwareAndTheCounter() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)

        fake.reportCamera(FakeCapabilities.FRONT_ID)
        fake.holdNextCapture()
        val capture = async { holder.capture() }
        runCurrent()

        val state = holder.uiState.value
        assertEquals(FakeCapabilities.FRONT_ID, state.cameraId)
        assertEquals(CameraLens.FRONT, state.cameraLens)
        assertEquals(1, state.pendingCaptures)
        assertTrue(state.isCapturing)
        assertNull(state.targetOrientation, "the fake reports no target orientation")
        fake.releaseCapture()
        assertIs<CameraKResult.Success<CapturedImage>>(capture.await())
        assertEquals(0, holder.uiState.value.pendingCaptures)

        val front = FakeCapabilities.backAndFront().allLenses.single { it.id == FakeCapabilities.FRONT_ID }
        val scripted = ScriptedHardwareEngine(
            FakeCameraEngine(),
            HardwareState(FlashMode.OFF, TorchMode.OFF, front, DeviceOrientation.LANDSCAPE_LEFT),
        )
        val other = readyHolder(scripted)
        assertEquals(DeviceOrientation.LANDSCAPE_LEFT, other.uiState.value.targetOrientation)
        scripted.reported.value = HardwareState(FlashMode.OFF, TorchMode.OFF, front, null)
        runCurrent()
        assertNull(other.uiState.value.targetOrientation, "a cleared target orientation")
    }
}

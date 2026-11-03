@file:OptIn(CameraKEngineApi::class, ExperimentalCoroutinesApi::class)
@file:Suppress("DEPRECATION")

package com.kashif.cameraK.state

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.HardwareState
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.FakeCapabilities
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
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
 * `uiState` between H2' and C1': still the 1.x data class, every field derived from what the engine
 * reports, the capture counter and the configuration, and nothing else writes it.
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

    @Test
    fun deprecatedZoomFieldsFollowTheZoomFlow() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)

        fake.reportZoom(3f, minRatio = 1f, maxRatio = 8f)
        runCurrent()

        assertEquals(ZoomState(3f, 1f, 8f), holder.zoom.value)
        assertEquals(3f, holder.uiState.value.zoomLevel)
        assertEquals(8f, holder.uiState.value.maxZoom)
    }

    @Test
    fun configurationFieldsComeFromTheConfiguration() = runTest {
        val config = CameraConfiguration(
            imageFormat = ImageFormat.PNG,
            qualityPrioritization = QualityPrioritization.QUALITY,
            cameraDeviceType = CameraDeviceType.WIDE_ANGLE,
        )
        val holder = CameraKStateHolder(config, { _, _ -> FakeCameraEngine() }, emptyList(), cameraKTestEnvironment())
        assertEquals(ImageFormat.PNG, holder.uiState.value.imageFormat, "before the first start")
        assertEquals(
            QualityPrioritization.QUALITY,
            holder.uiState.value.qualityPrioritization,
            "before the first start",
        )

        holder.start()
        runCurrent()

        assertEquals(ImageFormat.PNG, holder.uiState.value.imageFormat)
        assertEquals(QualityPrioritization.QUALITY, holder.uiState.value.qualityPrioritization)
    }

    @Test
    fun lastErrorStaysNullAfterAFailedCommand() = runTest {
        val fake = FakeCameraEngine()
        fake.captureResult = { CameraKResult.Failure(CameraKError.Disconnected()) }
        val holder = readyHolder(fake)

        assertIs<CameraKResult.Failure>(holder.capture())
        assertIs<CameraKResult.Failure>(holder.selectCamera("no-such-camera"))
        assertIs<CameraKResult.Failure>(holder.setTorchMode(TorchMode.AUTO))
        runCurrent()

        assertNull(holder.uiState.value.lastError)
    }
}

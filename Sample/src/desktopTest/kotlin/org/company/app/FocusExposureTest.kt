@file:OptIn(ExperimentalCoroutinesApi::class, CameraKEngineApi::class)

package org.company.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.FakeCapabilities
import com.kashif.cameraK.testing.fakeCameraKStateHolder
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.company.app.designsystem.exposureLabel
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Sample's focus and exposure UI (w13 Z-sample) on camerak-testing's fake, whose back camera meters
 * at a point and takes exposure indexes -6 to 6 in thirds of an EV: a tap shows the reticle, and the
 * exposure slider beside it for 3 s; a drag sets the exposure, and the slider reads what the camera
 * reports back. The scene is 4:3, as the fake's frame is, so its content rectangle is the whole scene.
 */
class FocusExposureTest {
    private fun TestScope.readyHolder(fake: FakeCameraEngine): CameraKStateHolder =
        fakeCameraKStateHolder(fake = fake).also {
            it.start()
            runCurrent()
            assertIs<CameraKState.Ready>(it.cameraState.value)
        }

    private fun TestScope.screen(holder: CameraKStateHolder, density: Float = 1f) =
        SampleScene(this, width = WIDTH, height = HEIGHT, density = density) {
            SampleCameraKScreen(holder, Modifier.fillMaxSize()) {}
        }

    @Test
    fun aTapShowsTheReticleThenTheSlider() = runTest {
        val holder = readyHolder(FakeCameraEngine())
        screen(holder).use { scene ->
            assertFalse(scene.shows(RETICLE), "a reticle before any tap")
            assertFalse(scene.shows(SLIDER), "a slider before any tap")

            scene.tapAt(Offset(200f, 150f))
            val reticle = scene.node(RETICLE).boundsInRoot
            assertEquals(72f, reticle.width, "the reticle's size")
            assertEquals(Offset(200f, 150f), reticle.center, "the reticle is centred on the tap")
            val slider = scene.node(SLIDER)
            assertEquals(reticle.right + 16f, slider.boundsInRoot.left, "the slider sits 16 dp right of the reticle")
            assertEquals("0.0 EV", slider.stateDescription())

            scene.settle(frames = 100)
            assertFalse(scene.shows(RETICLE), "the reticle outlived the 1.5 s hold after the camera answered")
            assertTrue(scene.shows(SLIDER), "the slider hid before 3 s had passed since the tap")
            scene.settle(frames = 90)
            assertFalse(scene.shows(SLIDER), "the slider was still shown 3 s after the tap")
        }
        holder.close()
    }

    @Test
    fun theSliderMovesLeftOfTheReticleWhenTheRightHasNoRoom() = runTest {
        val holder = readyHolder(FakeCameraEngine())
        screen(holder).use { scene ->
            scene.tapAt(Offset(380f, 150f))

            val reticle = scene.node(RETICLE).boundsInRoot
            assertEquals(reticle.left - 16f, scene.node(SLIDER).boundsInRoot.right, "the slider sits 16 dp left")
        }
        holder.close()
    }

    @Test
    fun withNoExposureRangeThereIsNoSlider() = runTest {
        val fake = FakeCameraEngine(capabilities = focusWithoutExposure())
        val holder = readyHolder(fake)
        screen(holder).use { scene ->
            scene.tapAt(Offset(200f, 150f))

            assertTrue(scene.shows(RETICLE), "no reticle on a camera that meters at a point")
            assertFalse(scene.shows(SLIDER), "a slider on a camera without exposure compensation")
        }
        holder.close()
    }

    @Test
    fun withoutAFocusPointThereIsNoReticle() = runTest {
        val fake = FakeCameraEngine(capabilities = FakeCapabilities.backAndFront(focusAndExposure = false))
        val holder = readyHolder(fake)
        screen(holder).use { scene ->
            scene.tapAt(Offset(200f, 150f))

            assertFalse(scene.shows(RETICLE), "a reticle on a camera without a focus point")
            assertFalse(scene.shows(SLIDER), "a slider without a focus tap")
            assertEquals(emptyList(), fake.calls.filter { it == "focusAt" })
        }
        holder.close()
    }

    @Test
    fun aDragSetsTheExposureAndTheSliderReadsTheCamerasReadBack() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)
        screen(holder).use { scene ->
            scene.tapAt(Offset(200f, 150f))
            // The track is 128 dp for the 12 steps from -6 to 6: 64 dp up is 6 steps brighter.
            scene.drag(scene.node(SLIDER), Offset(0f, -64f))

            assertTrue("setExposureCompensation" in fake.calls, "the drag did not set the exposure: ${fake.calls}")
            assertEquals(6, fake.appliedExposureCompensationIndex, "the index the drag reached")
            assertEquals(
                "0.0 EV",
                scene.node(SLIDER).stateDescription(),
                "the slider shows the drag, not what the camera reported",
            )

            fake.reportExposureIndex(2)
            scene.settle()
            assertEquals("+0.7 EV", scene.node(SLIDER).stateDescription(), "the slider does not read the read-back")
        }
        holder.close()
    }

    @Test
    fun theReticleIsIndigoOnceFocusedAndGreyWhenRefused() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)
        // At 2x the reticle's 0.5 dp white hairline is pixel 0 of its edge, the 1.5 dp stroke pixels 1 to 3
        // when focused; refused, the stroke is pixels 0 to 2. Pixel 2 is inside the stroke either way.
        screen(holder, density = 2f).use { scene ->
            scene.tapAt(Offset(400f, 300f))
            var image = Bitmap.makeFromImage(scene.settle())
            assertEquals(WHITE, image.getColor(400 - 72, 300), "the focused reticle's outer hairline")
            assertEquals(INDIGO, image.getColor(400 - 72 + 2, 300), "the focused reticle's stroke")

            scene.settle(frames = 100)
            fake.failNextFocus(CameraKError.Hardware(platformCode = null))
            scene.tapAt(Offset(400f, 300f))
            image = Bitmap.makeFromImage(scene.settle())
            assertEquals(GREY, image.getColor(400 - 72 + 2, 300), "the refused reticle's stroke")
        }
        holder.close()
    }

    @Test
    fun theExposureValueReadsInEvToOneDecimal() {
        assertEquals("+1.0 EV", exposureLabel(3, 1f / 3))
        assertEquals("-0.7 EV", exposureLabel(-2, 1f / 3))
        assertEquals("0.0 EV", exposureLabel(0, 1f / 3))
        assertEquals("+0.5 EV", exposureLabel(1, 0.5f))
        assertEquals("-2.0 EV", exposureLabel(-6, 1f / 3))
    }

    @Test
    fun aModeOrCameraSwitchResetsFocusAndExposure() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)
        var mode by mutableStateOf(CameraMode.Photo)
        SampleScene(this, width = 10, height = 10) { ResetFocusOnSwitch(holder, mode) }.use { scene ->
            assertEquals(0, fake.resets(), "a reset when the screen opened")

            mode = CameraMode.Qr
            scene.settle()
            assertEquals(1, fake.resets(), "a mode switch did not reset focus and exposure")

            assertIs<CameraKResult.Success<LensInfo>>(holder.selectCamera(CameraLens.FRONT))
            scene.settle()
            assertEquals(2, fake.resets(), "a camera switch did not reset focus and exposure")
        }
        holder.close()
    }

    @Test
    fun noResetOnACameraWithNeitherFocusNorExposure() = runTest {
        val fake = FakeCameraEngine(capabilities = FakeCapabilities.backAndFront(focusAndExposure = false))
        val holder = readyHolder(fake)
        var mode by mutableStateOf(CameraMode.Photo)
        SampleScene(this, width = 10, height = 10) { ResetFocusOnSwitch(holder, mode) }.use { scene ->
            mode = CameraMode.Video
            scene.settle()
            assertIs<CameraKResult.Success<LensInfo>>(holder.selectCamera(CameraLens.FRONT))
            scene.settle()

            assertEquals(0, fake.resets(), "a reset on cameras that support neither")
        }
        holder.close()
    }

    private fun FakeCameraEngine.resets(): Int = calls.count { it == "resetFocusAndExposure" }

    private fun SemanticsNode.stateDescription(): String? = config.getOrNull(SemanticsProperties.StateDescription)

    // The fake's two cameras, the back one metering at a point, neither with exposure compensation.
    private fun focusWithoutExposure(): CameraCapabilities {
        val base = FakeCapabilities.backAndFront(focusAndExposure = false)
        val ids = base.allLenses.map { it.id }
        return CameraCapabilities(
            allLenses = base.allLenses,
            flashModes = ids.associateWith(base::supportedFlashModes),
            torchModes = ids.associateWith(base::supportedTorchModes),
            supportsPauseResume = base.supportsPauseResume,
            supportedVideoQualities = base.supportedVideoQualities,
            supportsVideoBitrate = base.supportsVideoBitrate,
            supportedAspectRatios = base.supportedAspectRatios,
            supportedImageFormats = base.supportedImageFormats,
            supportedAnalysisFormats = base.supportedAnalysisFormats,
            supportsSquareCapture = base.supportsSquareCapture,
            focusPointCameras = setOf(FakeCapabilities.BACK_ID),
        )
    }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 300
        const val RETICLE = "Focus point"
        const val SLIDER = "Exposure"
        const val WHITE = 0xFFFFFFFF.toInt()
        const val INDIGO = 0xFF4F46E5.toInt()
        const val GREY = 0xFFA3A5AB.toInt()
    }
}

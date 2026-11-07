@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)

package com.kashif.cameraK.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.controls.NormalizedPoint
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.fakeCameraKStateHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Tap to focus on the preview (w12 Y-focus-api, G4): a tap inside the content rectangle reaches
 * `holder.focusAt` as a fraction of that rectangle, letterbox bars excluded, and the focus indicator
 * shows from the tap until 1.5 s after the camera answered. Over camerak-testing's fake engine, whose
 * preview publishes one 640 x 480 frame: in a 200 x 100 scene its content rectangle is 133 x 100,
 * from x = 34 to 167.
 */
class TapToFocusTest {
    private val engineCalls get() = fake.calls.filter { it == "focusAt" }
    private val fake = FakeCameraEngine()
    private val shown = AtomicReference<FocusTap?>(null)

    // Records the tap it is drawn for, and null once it leaves the composition.
    private val recordingIndicator: @Composable (FocusTap) -> Unit = { tap ->
        shown.set(tap)
        DisposableEffect(Unit) { onDispose { shown.set(null) } }
    }

    private fun TestScope.scene(
        holder: CameraKStateHolder,
        tapToFocus: Boolean = true,
        overlay: @Composable CameraPreviewScope.() -> Unit = {},
    ): ImageComposeScene = ImageComposeScene(
        width = 200,
        height = 100,
        density = Density(1f),
        coroutineContext = StandardTestDispatcher(testScheduler),
    ) {
        CameraPreview(
            holder,
            Modifier.fillMaxSize(),
            tapToFocus = tapToFocus,
            focusIndicator = recordingIndicator,
            overlay = overlay,
        )
    }.also { settle(it) }

    private fun TestScope.readyHolder(): CameraKStateHolder = fakeCameraKStateHolder(fake = fake).also {
        it.start()
        runCurrent()
        assertIs<CameraKState.Ready>(it.cameraState.value)
    }

    private fun TestScope.settle(scene: ImageComposeScene) {
        repeat(RENDERS) {
            runCurrent()
            scene.render()
        }
    }

    private fun TestScope.tap(scene: ImageComposeScene, at: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, at)
        scene.sendPointerEvent(PointerEventType.Release, at)
        settle(scene)
    }

    @Test
    fun aTapMapsToItsPlaceInTheContentRectangle() = runTest {
        val holder = readyHolder()
        val scene = scene(holder)
        try {
            tap(scene, Offset(34f + 66.5f, 50f))
            assertEquals(NormalizedPoint(0.5f, 0.5f), fake.lastFocusPoint, "a tap at the rectangle's centre")

            tap(scene, Offset(34f + 33.25f, 25f))
            assertEquals(NormalizedPoint(0.25f, 0.25f), fake.lastFocusPoint, "a tap a quarter in from the top-left")

            tap(scene, Offset(34f + 133f * 0.75f, 75f))
            assertEquals(NormalizedPoint(0.75f, 0.75f), fake.lastFocusPoint, "a tap a quarter in from the bottom-right")
            assertEquals(3, engineCalls.size, "focusAt calls: ${fake.calls}")
        } finally {
            scene.close()
            holder.close()
        }
    }

    @Test
    fun aTapOnTheBarsIsNotAFocusRequest() = runTest {
        val holder = readyHolder()
        val scene = scene(holder)
        try {
            tap(scene, Offset(10f, 50f))
            tap(scene, Offset(190f, 50f))

            assertEquals(emptyList(), engineCalls, "a tap on the pillarbox bars reached focusAt")
            assertNull(shown.get(), "an indicator showed for a tap on the bars")
        } finally {
            scene.close()
            holder.close()
        }
    }

    @Test
    fun theIndicatorShowsFromTheTapUntilOneAndAHalfSecondsAfterTheAnswer() = runTest {
        val holder = readyHolder()
        val scene = scene(holder)
        try {
            tap(scene, Offset(34f + 66.5f, 50f))
            val focused = shown.get()
            assertEquals(FocusTap(Offset(66.5f, 50f), NormalizedPoint(0.5f, 0.5f), FocusOutcome.FOCUSED), focused)

            advanceTimeBy(1_499)
            settle(scene)
            assertEquals(focused, shown.get(), "the indicator hid before 1.5 s had passed")
            advanceTimeBy(2)
            settle(scene)
            assertNull(shown.get(), "the indicator was still shown 1.5 s after the answer")

            fake.failNextFocus(CameraKError.Hardware(platformCode = null))
            tap(scene, Offset(34f + 33.25f, 25f))
            assertEquals(FocusOutcome.FAILED, shown.get()?.outcome, "the outcome of a refused focus")
        } finally {
            scene.close()
            holder.close()
        }
    }

    @Test
    fun aTapOnACameraWithoutAFocusPointDoesNothing() = runTest {
        val holder = readyHolder()
        assertIs<CameraKResult.Success<LensInfo>>(holder.selectCamera(CameraLens.FRONT))
        runCurrent()
        val scene = scene(holder)
        try {
            tap(scene, Offset(34f + 66.5f, 50f))

            assertEquals(emptyList(), engineCalls, "a tap on the fixed-focus camera reached focusAt")
            assertNull(shown.get(), "an indicator showed on a camera without a focus point")
        } finally {
            scene.close()
            holder.close()
        }
    }

    @Test
    fun withTapToFocusOffATapDoesNothing() = runTest {
        val holder = readyHolder()
        val scene = scene(holder, tapToFocus = false)
        try {
            tap(scene, Offset(34f + 66.5f, 50f))

            assertEquals(emptyList(), engineCalls)
            assertNull(shown.get())
        } finally {
            scene.close()
            holder.close()
        }
    }

    @Test
    fun anOverlayElementThatHandlesTheTapKeepsIt() = runTest {
        val holder = readyHolder()
        var clicks = 0
        val scene = scene(holder) {
            Box(Modifier.align(Alignment.Center).size(20.dp).clickable { clicks++ })
        }
        try {
            tap(scene, Offset(34f + 66.5f, 50f))
            assertEquals(1, clicks, "the overlay's button did not get the tap")
            assertEquals(emptyList(), engineCalls, "a tap the overlay handled also focused")

            tap(scene, Offset(34f + 10f, 10f))
            assertEquals(1, engineCalls.size, "a tap beside the button did not focus")
        } finally {
            scene.close()
            holder.close()
        }
    }

    private companion object {
        const val RENDERS = 20
    }
}

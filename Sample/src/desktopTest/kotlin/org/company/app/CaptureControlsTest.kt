@file:OptIn(ExperimentalCoroutinesApi::class)

package org.company.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The capture controls: the shutter shows what a tap does (a photo, a recording to start, the one to
 * stop), and the shutter row waits for a live camera, as the benchmarks read Flip Camera's arrival as
 * the camera being Ready.
 */
class CaptureControlsTest {
    private fun state(
        mode: CameraMode = CameraMode.Photo,
        cameraLive: Boolean = true,
        isRecording: Boolean = false,
        canPause: Boolean = true,
    ) = CaptureControlsState(
        mode = mode,
        cameraLive = cameraLive,
        isRecording = isRecording,
        isPaused = false,
        canPause = canPause,
        isCapturing = false,
        zoomStops = listOf(1f, 2f),
        zoom = 1f,
        thumbnail = null,
        rotationDegrees = 0f,
    )

    private val shutterLabels = listOf("Take photo", "Start recording", "Stop recording")

    @Test
    fun theShutterShowsTheRecordingVariantWhileRecording() = runTest {
        var current by mutableStateOf(state())
        var shutterTaps = 0
        SampleScene(this) {
            CaptureControls(
                state = current,
                onZoom = {},
                onModeChange = {},
                onShutter = { shutterTaps++ },
                onPauseToggle = {},
                onFlip = {},
            )
        }.use { scene ->
            assertEquals(listOf("Take photo"), shutterLabels.filter(scene::shows))

            current = state(mode = CameraMode.Video)
            scene.settle()
            assertEquals(listOf("Start recording"), shutterLabels.filter(scene::shows))

            current = state(mode = CameraMode.Video, isRecording = true)
            scene.settle()
            assertEquals(listOf("Stop recording"), shutterLabels.filter(scene::shows))
            assertTrue(scene.shows("Pause recording"))
            assertTrue(scene.shows("Lens switching is paused while recording"))
            val flip = scene.node("Flip Camera").config
            assertTrue(SemanticsProperties.Disabled in flip, "flip stays enabled while recording")

            scene.tap(scene.node("Stop recording"))
        }
        assertEquals(1, shutterTaps)
    }

    @Test
    fun noPauseWhereThePlatformCannotPause() = runTest {
        SampleScene(this) {
            CaptureControls(
                state = state(mode = CameraMode.Video, isRecording = true, canPause = false),
                onZoom = {},
                onModeChange = {},
                onShutter = {},
                onPauseToggle = {},
                onFlip = {},
            )
        }.use { scene -> assertFalse(scene.shows("Pause recording")) }
    }

    @Test
    fun theShutterRowWaitsForALiveCamera() = runTest {
        SampleScene(this) {
            CaptureControls(
                state = state(cameraLive = false),
                onZoom = {},
                onModeChange = {},
                onShutter = {},
                onPauseToggle = {},
                onFlip = {},
            )
        }.use { scene ->
            assertFalse(scene.shows("Flip Camera"))
            assertFalse(scene.shows("Take photo"))
            assertTrue(scene.shows("PHOTO"), "the modes show before the camera is live")
        }
    }
}

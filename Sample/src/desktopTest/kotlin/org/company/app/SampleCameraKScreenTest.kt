@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)

package org.company.app

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.fakeCameraKStateHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The Sample's camera screen on a fake holder: the starting slot while the camera opens, and the
 * error slot's fixed text for the error, never the error's own message.
 */
class SampleCameraKScreenTest {
    @Test
    fun startingShowsTheInitializingText() = runTest {
        val fake = FakeCameraEngine().apply { autoOpen = false }
        val holder = fakeCameraKStateHolder(fake = fake)
        holder.start()
        runCurrent()
        assertEquals(CameraKState.Starting, holder.cameraState.value)

        assertEquals(listOf("Initializing Camera..."), shownTexts(holder))
    }

    @Test
    fun anErrorShowsItsFixedText() = runTest {
        val fake = FakeCameraEngine().apply { autoOpen = false }
        val holder = fakeCameraKStateHolder(fake = fake)
        holder.start()
        runCurrent()
        fake.failOpen(CameraKError.NoCamera(lens = null, cause = IllegalStateException("/private/path/camera0")))
        runCurrent()
        assertIs<CameraKState.Error>(holder.cameraState.value)

        assertEquals(listOf("Error", "No camera found"), shownTexts(holder))
    }

    // The text and content descriptions on screen, in tree order, after the scene has settled.
    private fun TestScope.shownTexts(holder: CameraKStateHolder): List<String> {
        val scene = ImageComposeScene(width = WIDTH, height = HEIGHT) {
            SampleCameraKScreen(holder) {}
        }
        try {
            repeat(SETTLE_FRAMES) {
                Snapshot.sendApplyNotifications()
                scene.render()
                runCurrent()
            }
            return scene.semanticsOwners.flatMap { it.unmergedRootSemanticsNode.texts() }
        } finally {
            scene.close()
        }
    }

    private fun SemanticsNode.texts(): List<String> {
        val own =
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        return own + children.flatMap { it.texts() }
    }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 300
        const val SETTLE_FRAMES = 3
    }
}

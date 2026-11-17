@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kashifmehmood.camerak.permissions

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * macOS's prompt is answered outside the app's lifecycle: the window may never resume after it, so
 * the desktop permission state reads the statuses again while a prompt may be up, and stops after.
 */
class RefreshWhilePromptPendingTest {
    private var camera: PermissionStatus = PermissionStatus.NotDetermined
    private var reads = 0
    private val state = PlatformPermissionState(listOf(CameraKPermission.CAMERA)) {
        reads++
        camera
    }

    private fun TestScope.compose(): ImageComposeScene {
        val scene = ImageComposeScene(10, 10, coroutineContext = StandardTestDispatcher(testScheduler)) {
            RefreshWhilePromptPending(state) { camera == PermissionStatus.NotDetermined }
        }
        frame(scene)
        return scene
    }

    private fun TestScope.frame(scene: ImageComposeScene) {
        Snapshot.sendApplyNotifications()
        scene.render()
        runCurrent()
    }

    @Test
    fun aPromptAnsweredWithoutAResumeReachesTheStatuses() = runTest {
        val scene = compose()
        try {
            camera = PermissionStatus.Granted
            advanceTimeBy(PROMPT_POLL_INTERVAL * 2)
            frame(scene)

            assertEquals(PermissionStatus.Granted, state.statuses[CameraKPermission.CAMERA])
        } finally {
            scene.close()
        }
    }

    @Test
    fun theReadsStopOnceThePromptIsAnswered() = runTest {
        val scene = compose()
        try {
            camera = PermissionStatus.PermanentlyDenied
            advanceTimeBy(PROMPT_POLL_INTERVAL * 2)
            frame(scene)
            val answeredAt = reads
            advanceTimeBy(PROMPT_POLL_INTERVAL * 20)
            frame(scene)

            assertEquals(PermissionStatus.PermanentlyDenied, state.statuses[CameraKPermission.CAMERA])
            assertEquals(answeredAt, reads, "the state kept reading after the answer")
        } finally {
            scene.close()
        }
    }
}

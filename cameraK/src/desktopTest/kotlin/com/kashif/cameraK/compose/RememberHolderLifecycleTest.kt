@file:OptIn(CameraKEngineApi::class, ExperimentalCoroutinesApi::class)

package com.kashif.cameraK.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.FakePermissionChecker
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * The default Compose path without a ViewModelStoreOwner: rememberCameraKStateHolder starts the
 * holder when the host reaches STARTED, stops it below STARTED, re-checks the permission on every
 * resume and closes the holder when it leaves the composition. RetainedHolderTest covers the path
 * with an owner.
 */
class RememberHolderLifecycleTest {
    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class Rig(scope: TestScope, val fake: FakeCameraEngine, val checker: FakePermissionChecker) {
        val owner = TestOwner()
        var shown by mutableStateOf(true)
        var holder: CameraKStateHolder? = null
        private val environment = with(scope) { cameraKTestEnvironment(checker) }
        private val scene = ImageComposeScene(width = 10, height = 10) {
            if (shown) {
                holder = rememberCameraKStateHolder(
                    config = CameraConfiguration(),
                    plugins = emptyList(),
                    lifecycleOwner = owner,
                    engineFactory = { _, _ -> fake },
                    environment = environment,
                    // The path with no ViewModelStoreOwner. A bare scene's own owner is one for the
                    // whole process and is never cleared, so a holder retained in it would outlive
                    // the scene and be shared with the next test's.
                    viewModelStoreOwner = null,
                )
            }
        }

        val state: CameraKState get() = assertNotNull(holder, "nothing was remembered").cameraState.value

        fun frame(scope: TestScope) {
            Snapshot.sendApplyNotifications()
            scene.render()
            scope.runCurrent()
        }

        fun moveTo(state: Lifecycle.State, scope: TestScope) {
            owner.registry.currentState = state
            frame(scope)
        }

        fun close() = scene.close()
    }

    private fun TestScope.rig(
        fake: FakeCameraEngine = FakeCameraEngine(),
        checker: FakePermissionChecker = FakePermissionChecker(),
    ) = Rig(this, fake, checker)

    @Test
    fun permissionGrantedWhileErrorThenResumeReachesReady() = runTest {
        val checker = FakePermissionChecker(PermissionStatus.Denied(shouldShowRationale = true))
        val rig = rig(checker = checker)
        try {
            rig.frame(this)
            rig.moveTo(Lifecycle.State.RESUMED, this)
            assertIs<CameraKState.Error>(rig.state, "state is ${rig.state}")

            // The permission dialog pauses and resumes the host without a stop.
            rig.moveTo(Lifecycle.State.STARTED, this)
            checker.setStatus(CameraKPermission.CAMERA, PermissionStatus.Granted)
            rig.moveTo(Lifecycle.State.RESUMED, this)

            assertIs<CameraKState.Ready>(rig.state, "state is ${rig.state}")
        } finally {
            rig.close()
        }
    }

    @Test
    fun startedOwnerStartsAndStoppedOwnerStopsTheHolder() = runTest {
        val rig = rig()
        try {
            rig.moveTo(Lifecycle.State.CREATED, this)
            assertEquals(CameraKState.Idle, rig.state, "started below STARTED")

            rig.moveTo(Lifecycle.State.STARTED, this)
            assertIs<CameraKState.Ready>(rig.state, "state is ${rig.state}")

            rig.moveTo(Lifecycle.State.CREATED, this)
            assertEquals(CameraKState.Idle, rig.state, "not stopped below STARTED")
            assertEquals(listOf("start", "stop"), rig.fake.calls)
        } finally {
            rig.close()
        }
    }

    @Test
    fun disposeClosesTheHolder() = runTest {
        val rig = rig()
        try {
            rig.moveTo(Lifecycle.State.RESUMED, this)
            assertIs<CameraKState.Ready>(rig.state, "state is ${rig.state}")

            rig.shown = false
            rig.frame(this)

            assertEquals(CameraKState.Closed, rig.state)
            assertEquals(1, rig.fake.closeCalls, "engine closes: ${rig.fake.calls}")
        } finally {
            rig.close()
        }
    }
}

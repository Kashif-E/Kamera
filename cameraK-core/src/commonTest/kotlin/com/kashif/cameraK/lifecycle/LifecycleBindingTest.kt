@file:OptIn(CameraKEngineApi::class, ExperimentalCoroutinesApi::class)

package com.kashif.cameraK.lifecycle

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.kashif.cameraK.engine.CameraEngineFactory
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

/**
 * `bindToLifecycle` (design §7.2), the app-owned path: at least STARTED starts the holder, below it
 * stops it, each move to RESUMED re-checks the permission, and closing the binding stops the holder
 * and stops following the lifecycle.
 */
class LifecycleBindingTest {
    private class Host : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun TestScope.holderOver(fake: FakeCameraEngine, checker: FakePermissionChecker) = CameraKStateHolder(
        CameraConfiguration(),
        CameraEngineFactory { _, _ -> fake },
        emptyList(),
        cameraKTestEnvironment(checker),
    )

    @Test
    fun closingTheBindingStopsTheHolder() = runTest {
        val fake = FakeCameraEngine()
        val holder = holderOver(fake, FakePermissionChecker())
        val host = Host()
        val binding = holder.bindToLifecycle(host.registry)

        host.registry.currentState = Lifecycle.State.STARTED
        runCurrent()
        assertIs<CameraKState.Ready>(holder.cameraState.value, "STARTED did not start the holder")

        binding.close()
        runCurrent()
        assertEquals(CameraKState.Idle, holder.cameraState.value, "closing the binding did not stop the holder")
        assertEquals(listOf("start", "stop"), fake.calls)

        host.registry.currentState = Lifecycle.State.RESUMED
        runCurrent()
        assertEquals(CameraKState.Idle, holder.cameraState.value, "a closed binding still follows the lifecycle")
        assertEquals(listOf("start", "stop"), fake.calls)
    }

    @Test
    fun resumeRechecksPermission() = runTest {
        val checker = FakePermissionChecker(PermissionStatus.Denied(shouldShowRationale = true))
        val holder = holderOver(FakeCameraEngine(), checker)
        val host = Host()
        holder.bindToLifecycle(host.registry)

        host.registry.currentState = Lifecycle.State.RESUMED
        runCurrent()
        assertIs<CameraKState.Error>(holder.cameraState.value, "state is ${holder.cameraState.value}")

        // The system dialog pauses and resumes the host without a stop.
        host.registry.currentState = Lifecycle.State.STARTED
        runCurrent()
        checker.setStatus(CameraKPermission.CAMERA, PermissionStatus.Granted)
        host.registry.currentState = Lifecycle.State.RESUMED
        runCurrent()

        assertIs<CameraKState.Ready>(holder.cameraState.value, "the resume did not re-check the permission")
    }
}

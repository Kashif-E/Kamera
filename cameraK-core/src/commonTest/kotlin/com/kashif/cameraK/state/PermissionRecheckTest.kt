@file:OptIn(CameraKEngineApi::class, ExperimentalCoroutinesApi::class)

package com.kashif.cameraK.state

import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.FakePermissionChecker
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

/**
 * A permission granted while the holder shows Error(Permission) reaches Ready on the next re-check,
 * with no retry: the host's resume calls recheckPermission().
 */
class PermissionRecheckTest {
    private fun TestScope.holder(fake: FakeCameraEngine, checker: FakePermissionChecker): CameraKStateHolder =
        CameraKStateHolder(CameraConfiguration(), { _, _ -> fake }, emptyList(), cameraKTestEnvironment(checker))

    private fun CameraKStateHolder.permissionError(): CameraKError.Permission {
        val state = cameraState.value
        return assertIs(assertIs<CameraKState.Error>(state, "state is $state").error)
    }

    @Test
    fun grantWhileErrorPermissionThenRecheckReachesReadyWithoutRetry() = runTest {
        val fake = FakeCameraEngine()
        val checker = FakePermissionChecker(PermissionStatus.Denied(shouldShowRationale = false))
        val holder = holder(fake, checker)
        holder.start()
        runCurrent()
        holder.permissionError()

        checker.setStatus(CameraKPermission.CAMERA, PermissionStatus.Granted)
        holder.recheckPermission()
        runCurrent()

        assertIs<CameraKState.Ready>(holder.cameraState.value, "state is ${holder.cameraState.value}")
        assertEquals(1, fake.startCalls)
    }

    @Test
    fun restrictedIsNotRechecked() = runTest {
        val fake = FakeCameraEngine()
        val checker = FakePermissionChecker(PermissionStatus.Restricted)
        val holder = holder(fake, checker)
        holder.start()
        runCurrent()
        holder.permissionError()

        checker.setStatus(CameraKPermission.CAMERA, PermissionStatus.Granted)
        holder.recheckPermission()
        runCurrent()

        assertEquals(PermissionStatus.Restricted, holder.permissionError().status)
        assertEquals(0, fake.startCalls)
    }

    @Test
    fun recheckOutsidePermissionErrorDoesNothing() = runTest {
        val fake = FakeCameraEngine().apply { autoOpen = false }
        val holder = holder(fake, FakePermissionChecker())

        holder.recheckPermission()
        runCurrent()
        assertEquals(CameraKState.Idle, holder.cameraState.value, "a re-check started an idle holder")

        holder.start()
        runCurrent()
        fake.failOpen(CameraKError.CameraInUse())
        runCurrent()
        assertIs<CameraKState.Error>(holder.cameraState.value)

        holder.recheckPermission()
        runCurrent()

        assertIs<CameraKError.CameraInUse>(assertIs<CameraKState.Error>(holder.cameraState.value).error)
        assertEquals(1, fake.startCalls, "a re-check outside Error(Permission) restarted the engine")
    }

    @Test
    fun startTimeoutWhileNotDeterminedGivesPermissionNotDetermined() = runTest {
        val fake = FakeCameraEngine().apply { autoOpen = false }
        val checker = FakePermissionChecker(PermissionStatus.NotDetermined).apply { allowsOpenWhenNotDetermined = true }
        val holder = holder(fake, checker)
        holder.start()
        runCurrent()
        assertEquals(CameraKState.Starting, holder.cameraState.value, "the checker allows opening at NotDetermined")

        advanceTimeBy(START_TIMEOUT + 1.milliseconds)
        runCurrent()

        val permission = holder.permissionError()
        assertEquals(CameraKPermission.CAMERA, permission.permission)
        assertEquals(PermissionStatus.NotDetermined, permission.status)
    }
}

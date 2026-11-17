@file:OptIn(CameraKEngineApi::class, ExperimentalCoroutinesApi::class)

package dev.kashifmehmood.camerak.state

import dev.kashifmehmood.camerak.error.CameraKError
import dev.kashifmehmood.camerak.error.CameraOperation
import dev.kashifmehmood.camerak.permissions.CameraKPermission
import dev.kashifmehmood.camerak.permissions.PermissionStatus
import dev.kashifmehmood.camerak.testing.FakeCameraEngine
import dev.kashifmehmood.camerak.testing.FakePermissionChecker
import dev.kashifmehmood.camerak.testing.cameraKTestEnvironment
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
    fun aStartWaitingOnTheCameraPromptOutlastsTheStartTimeout() = runTest {
        // macOS shows its camera prompt when the camera opens; the open returns once it is answered.
        val fake = FakeCameraEngine().apply { autoOpen = false }
        val checker = FakePermissionChecker(PermissionStatus.NotDetermined).apply { allowsOpenWhenNotDetermined = true }
        val holder = holder(fake, checker)
        holder.start()
        runCurrent()
        assertEquals(CameraKState.Starting, holder.cameraState.value, "the checker allows opening at NotDetermined")

        // The user reads the prompt for longer than a start may otherwise take, then allows.
        advanceTimeBy(START_TIMEOUT + 5.seconds)
        runCurrent()
        assertEquals(CameraKState.Starting, holder.cameraState.value, "the start gave up while the prompt was up")
        checker.setStatus(CameraKPermission.CAMERA, PermissionStatus.Granted)
        fake.deliverOpen()
        runCurrent()

        assertIs<CameraKState.Ready>(holder.cameraState.value)
        assertEquals(1, fake.startCalls)
    }

    @Test
    fun aCameraPromptNeverAnsweredGivesPermissionNotDetermined() = runTest {
        val fake = FakeCameraEngine().apply { autoOpen = false }
        val checker = FakePermissionChecker(PermissionStatus.NotDetermined).apply { allowsOpenWhenNotDetermined = true }
        val holder = holder(fake, checker)
        holder.start()
        runCurrent()

        advanceTimeBy(START_TIMEOUT + PERMISSION_PROMPT_TIMEOUT - 1.milliseconds)
        runCurrent()
        assertEquals(CameraKState.Starting, holder.cameraState.value)
        advanceTimeBy(2.milliseconds)
        runCurrent()

        val permission = holder.permissionError()
        assertEquals(CameraKPermission.CAMERA, permission.permission)
        assertEquals(PermissionStatus.NotDetermined, permission.status)
    }

    @Test
    fun aNotDeterminedThatShowsNoPromptTimesOutAtTheStartTimeout() = runTest {
        // A Mac that could not read its authorization: the camera may open, but no prompt is up to wait on.
        val fake = FakeCameraEngine().apply { autoOpen = false }
        val checker = FakePermissionChecker(PermissionStatus.NotDetermined).apply {
            allowsOpenWhenNotDetermined = true
            promptsWhenNotDetermined = false
        }
        val holder = holder(fake, checker)
        holder.start()
        runCurrent()

        advanceTimeBy(START_TIMEOUT + 1.milliseconds)
        runCurrent()

        val error = assertIs<CameraKState.Error>(holder.cameraState.value).error
        assertEquals(CameraOperation.START, assertIs<CameraKError.Timeout>(error).operation)
    }

    @Test
    fun aCameraPromptDeniedWhileTheOpenHangsGivesTheStatusItWasDeniedWith() = runTest {
        val fake = FakeCameraEngine().apply { autoOpen = false }
        val checker = FakePermissionChecker(PermissionStatus.NotDetermined).apply { allowsOpenWhenNotDetermined = true }
        val holder = holder(fake, checker)
        holder.start()
        runCurrent()

        advanceTimeBy(5.seconds)
        checker.setStatus(CameraKPermission.CAMERA, PermissionStatus.PermanentlyDenied)
        advanceTimeBy(START_TIMEOUT + PERMISSION_PROMPT_TIMEOUT)
        runCurrent()

        val permission = holder.permissionError()
        assertEquals(CameraKPermission.CAMERA, permission.permission)
        assertEquals(
            PermissionStatus.PermanentlyDenied,
            permission.status,
            "the user denied it: Settings, not a prompt",
        )
    }
}

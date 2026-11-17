@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.permissions

import dev.kashifmehmood.camerak.error.CameraKError
import dev.kashifmehmood.camerak.platform.CameraKPlatformContext
import dev.kashifmehmood.camerak.state.CameraConfiguration
import dev.kashifmehmood.camerak.state.CameraKState
import dev.kashifmehmood.camerak.state.CameraKStateHolder
import dev.kashifmehmood.camerak.state.productionEnvironment
import dev.kashifmehmood.camerak.testing.FakeCameraEngine
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * The production constructor's environment carries the platform checker, not the Granted stub: on
 * macOS a camera the user denied ends the start in Error(Permission) before the engine opens.
 */
class PlatformCheckerReplacesStubTest {
    @Test
    fun holderOverThePlatformCheckerReportsDeniedAsPermissionError() {
        val previous = desktopPermissionChecker
        desktopPermissionChecker = DesktopPermissionChecker("Mac OS X") { PermissionStatus.PermanentlyDenied }
        val fake = FakeCameraEngine()
        val holder = CameraKStateHolder(
            CameraConfiguration(),
            { _, _ -> fake },
            emptyList(),
            productionEnvironment(CameraKPlatformContext()),
        )
        try {
            holder.start()
            val settled = runBlocking {
                withTimeout(10.seconds) {
                    holder.cameraState.first { it is CameraKState.Error || it is CameraKState.Ready }
                }
            }

            val state = assertIs<CameraKState.Error>(settled, "state is $settled")
            val error = assertIs<CameraKError.Permission>(state.error)
            assertEquals(CameraKPermission.CAMERA, error.permission)
            assertEquals(PermissionStatus.PermanentlyDenied, error.status)
            assertEquals(0, fake.startCalls, "the engine opened without the permission")
        } finally {
            holder.close()
            desktopPermissionChecker = previous
        }
    }
}

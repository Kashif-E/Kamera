@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.permissions

import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.state.productionEnvironment
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVMediaTypeVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * The production constructor's environment carries the platform checker, not the Granted stub: a
 * camera AVFoundation reports denied ends the start in Error(Permission) before the engine opens.
 * The simulator's own status cannot be scripted, so the status read is the checker's seam.
 */
class PlatformCheckerReplacesStubTest {
    @Test
    fun holderOverThePlatformCheckerReportsDeniedAsPermissionError() {
        val platform = FakeApplePermissionPlatform(
            capture = mutableMapOf(
                AVMediaTypeVideo to AVAuthorizationStatusDenied,
            ),
        )
        withPlatform(platform) {
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
            }
        }
    }
}

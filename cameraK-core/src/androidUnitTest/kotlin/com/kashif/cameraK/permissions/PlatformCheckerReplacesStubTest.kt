@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.permissions

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * The production constructor's environment carries the platform checker, not the Granted stub: a
 * camera permission the system has not granted ends the start in Error(Permission) before the engine
 * opens.
 */
@RunWith(RobolectricTestRunner::class)
class PlatformCheckerReplacesStubTest {
    @Test
    fun holderOverThePlatformCheckerReportsDeniedAsPermissionError() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        val fake = FakeCameraEngine()
        val holder = CameraKStateHolder(
            CameraConfiguration(),
            { _, _ -> fake },
            emptyList(),
            productionEnvironment(CameraKPlatformContext(app)),
        )
        try {
            holder.start()
            val settled = runBlocking {
                withTimeout(10.seconds) {
                    holder.cameraState.first { it is CameraKState.Error || it is CameraKState.Ready }
                }
            }

            val error =
                assertIs<CameraKError.Permission>(assertIs<CameraKState.Error>(settled, "state is $settled").error)
            assertEquals(CameraKPermission.CAMERA, error.permission)
            assertEquals(PermissionStatus.NotDetermined, error.status, "never asked")
            assertEquals(0, fake.startCalls, "the engine opened without the permission")
        } finally {
            holder.close()
        }
    }
}

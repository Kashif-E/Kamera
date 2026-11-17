@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import androidx.camera.core.CameraState
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * GitHub #189: when the system takes the camera away (ERROR_CAMERA_DISABLED: device policy, or the
 * app's uid going idle), CameraX closes it and keeps a capture's request queued for a camera it does
 * not reopen. The capture ends with the camera's error, and a file CameraX writes for it later, once
 * the camera opens again, is deleted rather than left unreported.
 */
@RunWith(RobolectricTestRunner::class)
class CameraDisabledCaptureTest {
    private val platform = FakeAndroidPlatform()

    @Test
    fun aCaptureWaitingWhenTheCameraIsDisabledEndsWithCameraDisabledAndLeavesNoFile() = runTest {
        val stills = File(platform.context.filesDir, "stills")
        val engine = platform.engine(testScheduler, CameraConfiguration { outputDirectory = stills.path })
        open(platform, engine)
        val capture = async { engine.capture() }
        platform.settle(testScheduler)

        checkNotNull(platform.camera).report(
            CameraState.create(
                CameraState.Type.CLOSING,
                CameraState.StateError.create(CameraState.ERROR_CAMERA_DISABLED),
            ),
        )
        platform.settle(testScheduler)

        val failed = assertIs<EngineState.Failed>(engine.state.value)
        assertIs<CameraKError.CameraDisabled>(failed.error)
        assertTrue(capture.isCompleted, "the capture waits for a camera CameraX will not reopen")
        assertSame(failed.error, assertIs<CameraKResult.Failure>(capture.await()).error)

        platform.backend.captures.single().deliverSaved(byteArrayOf(1, 2, 3))
        platform.settle(testScheduler)
        assertEquals(emptyList(), stills.listFiles().orEmpty().toList(), "a file nobody was told about")
        engine.closeAndJoin()
    }
}

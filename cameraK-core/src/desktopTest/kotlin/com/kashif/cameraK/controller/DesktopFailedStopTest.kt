@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.BackoffPolicy
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Failed ends its generation, but the session it ended may still hold the camera: the reopen in
 * flight when the recovery gave up is parked in `grab()` with the device lock. `stop()` from Failed
 * releases both, so another engine on the same device opens (§2.3 rule 1).
 */
class DesktopFailedStopTest {
    private val desired = DesiredHardware(
        selection = CameraSelection.ByLens(CameraLens.BACK, CameraDeviceType.DEFAULT),
        flashMode = FlashMode.OFF,
        torchMode = TorchMode.OFF,
        zoomRatio = 1f,
        targetOrientation = null,
    )

    @Test
    fun stopAfterFailedReleasesTheGrabberAndTheDeviceLock() = runTest {
        val rig = DesktopEngineRig(testScheduler, backoff = BackoffPolicy { RECOVERY_TIMEOUT / 4 })
        val first = rig.engine()
        val second = rig.engine()
        try {
            val start = async { first.start(desired) }
            rig.settle()
            assertEquals(CameraKResult.Success(Unit), start.await())

            // The camera goes silent and stays silent through every reopen, until the recovery gives up.
            rig.camera.holdOpen = true
            advanceTimeBy(NO_FRAME_TIMEOUT + RECOVERY_TIMEOUT)
            runCurrent()
            rig.settle()
            assertIs<CameraKError.Disconnected>(assertIs<EngineState.Failed>(first.state.value).error)

            val stop = async { first.stop() }
            rig.settle()
            assertTrue(stop.isCompleted, "stop from Failed did not return")
            assertEquals(EngineState.Closed, first.state.value)
            assertEquals(rig.camera.startCount, rig.camera.releaseCount, "stop from Failed left the camera open")

            // Same camera, same device lock: the second engine opens only if the first let go.
            rig.camera.holdOpen = false
            val reopened = async { second.start(desired) }
            rig.settle()
            assertTrue(reopened.isCompleted, "the second engine is still waiting for the device")
            assertEquals(CameraKResult.Success(Unit), reopened.await())
            assertEquals(EngineState.Open, second.state.value)
        } finally {
            first.close()
            second.close()
        }
    }
}

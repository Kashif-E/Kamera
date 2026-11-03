@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A camera that stops delivering frames leaves FFmpeg's `grab()` sleeping and retrying on
 * AVERROR(EAGAIN) until the thread is interrupted (javacv 1.5.12 `grabFrame`). [FakeFrameGrabber]
 * copies that loop, so a stop or a cancelled start can reach the camera only by interrupting the
 * grab: the grabber and the device lock must still be released within a bound (§2.3 rule 8).
 */
class GrabStallTest {
    private val desired = DesiredHardware(
        selection = CameraSelection.ByLens(CameraLens.BACK, CameraDeviceType.DEFAULT),
        flashMode = FlashMode.OFF,
        torchMode = TorchMode.OFF,
        zoomRatio = 1f,
        targetOrientation = null,
    )

    private fun engine(camera: FakeFrameGrabber) = DesktopCameraEngine(
        CameraConfiguration(),
        CameraKEnvironment(CameraKDispatchers(main = Dispatchers.Default, default = Dispatchers.Default)),
        CustomGrabberSource(camera),
    )

    @Test
    fun stopReleasesGrabberAndLockWithinBoundWhileGrabSpins() = runBlocking {
        val camera = FakeFrameGrabber(spinUntilInterrupted = true).apply { allowGrabs(1) }
        val sut = engine(camera)
        try {
            assertEquals(CameraKResult.Success(Unit), withTimeout(BOUND_MS) { sut.start(desired) })
            assertTrue(camera.spinning.await(BOUND_MS, TimeUnit.MILLISECONDS), "grab() never started spinning")

            withTimeout(BOUND_MS) { sut.stop() }

            assertEquals(1, camera.releaseCount.get(), "the stalled grabber was not released when stop returned")
            assertEquals(EngineState.Closed, sut.state.value)
            assertEquals(emptyList(), camera.violations.toList())
        } finally {
            sut.close()
        }
    }

    @Test
    fun cancelledStartWhileGrabSpinsLeavesCameraClosed() = runBlocking {
        val camera = FakeFrameGrabber(spinUntilInterrupted = true)
        val sut = engine(camera)
        try {
            val start = async(Dispatchers.Default) { sut.start(desired) }
            assertTrue(camera.spinning.await(BOUND_MS, TimeUnit.MILLISECONDS), "grab() never started spinning")
            assertEquals(EngineState.Opening, sut.state.value, "a start with no frame yet")

            withTimeout(BOUND_MS) { start.cancelAndJoin() }

            assertEquals(EngineState.Closed, sut.state.value, "a cancelled start left the camera open")
            assertTrue(camera.awaitReleases(1, BOUND_MS / 1_000), "the spinning grabber was never released")
            assertEquals(emptyList(), camera.violations.toList())
        } finally {
            sut.close()
        }
    }

    @Test
    fun secondEngineReachesOpenAfterStalledFirstIsStopped() = runBlocking {
        // One camera, one device lock: the second engine opens only once the first released it.
        val camera = FakeFrameGrabber(spinUntilInterrupted = true).apply { allowGrabs(1) }
        val first = engine(camera)
        val second = engine(camera)
        try {
            assertEquals(CameraKResult.Success(Unit), withTimeout(BOUND_MS) { first.start(desired) })
            assertTrue(camera.spinning.await(BOUND_MS, TimeUnit.MILLISECONDS), "grab() never started spinning")

            withTimeout(BOUND_MS) { first.stop() }
            camera.allowGrabs(1)

            assertEquals(CameraKResult.Success(Unit), withTimeout(BOUND_MS) { second.start(desired) })
            assertEquals(EngineState.Open, second.state.value)
            assertEquals(emptyList(), camera.violations.toList(), "camera accesses that overlap")
        } finally {
            first.close()
            second.close()
        }
    }

    private companion object {
        // Far above a 10 ms sleep, far below a hang.
        const val BOUND_MS = 5_000L
    }
}

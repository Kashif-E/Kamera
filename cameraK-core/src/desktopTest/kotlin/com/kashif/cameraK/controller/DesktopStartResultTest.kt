@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.Resolution
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * The desktop start reports its result (desktop-unowned-scopes): a camera that does not open is
 * a returned Failure with Failed already published, and the engine is never Open on the way.
 */
class DesktopStartResultTest {
    private val desired = DesiredHardware(
        selection = CameraSelection.ByLens(CameraLens.BACK, CameraDeviceType.DEFAULT),
        flashMode = FlashMode.OFF,
        torchMode = TorchMode.OFF,
        zoomRatio = 1f,
        targetOrientation = null,
    )

    @Test
    fun startFailureIsFailedNeverOpen() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val failure = FrameGrabber.Exception("avformat_open_input() error -5: Could not open input \"0\".")
        rig.camera.startFailure = failure
        val sut = rig.engine()
        val states = CopyOnWriteArrayList<EngineState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { sut.state.collect { states += it } }
        try {
            val start = async { sut.start(desired).let { it to sut.state.value } }
            rig.settle()

            val (result, atReturn) = start.await()
            val error = assertIs<CameraKError.Hardware>(assertIs<CameraKResult.Failure>(result).error)
            assertEquals(-5, error.platformCode)
            assertSame(failure, error.cause)
            val published = assertIs<EngineState.Failed>(atReturn, "the state when start returned")
            assertSame(error, published.error, "Failed carries the error start returned")
            assertFalse(EngineState.Open in states, "the engine was Open on the way to failing: $states")
            assertEquals(0, rig.camera.startCount, "a camera that never opened was counted as started")
        } finally {
            sut.close()
        }
    }

    @Test
    fun unknownCameraIdIsNoCameraWithoutOpening() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val sut = rig.engine()
        try {
            rig.settle()
            val result = sut.start(desiredOn(CameraSelection.ById("not-a-device")))

            assertIs<CameraKError.NoCamera>(assertIs<CameraKResult.Failure>(result).error)
            assertIs<EngineState.Failed>(sut.state.value)
            assertEquals(null, rig.openedDeviceId, "a camera was opened for a selection that matches none")
        } finally {
            sut.close()
        }
    }

    @Test
    fun aTargetResolutionTheCameraOpensOnlyWithoutIsUnsupported() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val sut = DesktopCameraEngine(
            CameraConfiguration { targetResolution = Resolution(7680, 4320) },
            rig.environment(),
            RelaxingSource(rig.source),
            authorization = { PermissionStatus.Granted },
        )
        try {
            val start = async { sut.start(desired) }
            rig.settle()

            val error = assertIs<CameraKError.Unsupported>(assertIs<CameraKResult.Failure>(start.await()).error)
            assertEquals(CameraFeature.TARGET_RESOLUTION, error.feature)
            assertEquals(rig.camera.startCount, rig.camera.releaseCount, "the camera opened at its own mode was kept")
        } finally {
            sut.close()
        }
    }

    /** The rig's camera behind a source that fails the constrained open and allows the retry. */
    private class RelaxingSource(private val camera: GrabberSource) : GrabberSource by camera {
        override val canRelax: Boolean = true

        override fun create(device: LensInfo, constrained: Boolean): FrameGrabber =
            if (constrained) RefusesConstraints() else camera.create(device, constrained)
    }

    private class RefusesConstraints : FrameGrabber() {
        override fun start(): Unit = throw Exception("avformat_open_input() error -22: Invalid argument")

        override fun stop() = Unit

        override fun trigger() = Unit

        override fun grab(): Frame? = null

        override fun release() = Unit
    }

    private fun desiredOn(selection: CameraSelection) = DesiredHardware(
        selection = selection,
        flashMode = desired.flashMode,
        torchMode = desired.torchMode,
        zoomRatio = desired.zoomRatio,
        targetOrientation = desired.targetOrientation,
    )
}

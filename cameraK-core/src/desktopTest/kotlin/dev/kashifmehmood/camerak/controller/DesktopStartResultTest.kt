@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.capabilities.LensInfo
import dev.kashifmehmood.camerak.engine.CameraSelection
import dev.kashifmehmood.camerak.engine.DesiredHardware
import dev.kashifmehmood.camerak.engine.EngineState
import dev.kashifmehmood.camerak.enums.CameraDeviceType
import dev.kashifmehmood.camerak.enums.CameraLens
import dev.kashifmehmood.camerak.enums.FlashMode
import dev.kashifmehmood.camerak.enums.TorchMode
import dev.kashifmehmood.camerak.error.CameraKError
import dev.kashifmehmood.camerak.permissions.PermissionStatus
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.state.CameraConfiguration
import dev.kashifmehmood.camerak.state.Resolution
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
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

    // targetResolution is a target, as on Android and iOS: a camera that opens only without it streams
    // at its own mode rather than failing the start.
    @Test
    fun aTargetResolutionTheCameraOpensOnlyWithoutStartsAtTheCamerasOwnMode() = runTest {
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

            assertEquals(CameraKResult.Success(Unit), start.await())
            assertIs<EngineState.Open>(sut.state.value)
            assertEquals(1, rig.camera.startCount - rig.camera.releaseCount, "the camera opened at its own mode")
        } finally {
            sut.close()
        }
    }

    // On macOS the source reads the camera's modes and asks for the listed size nearest the target, so
    // no avfoundation open fails (a failed open, then a quick reopen and release, crashed the app).
    @Test
    fun aTargetResolutionTheCameraLacksOpensAtTheListedSizeNearestIt() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val asked = mutableListOf<Pair<Int, Int>>()
        val sut = DesktopCameraEngine(
            CameraConfiguration { targetResolution = Resolution(7680, 4320) },
            rig.environment(),
            object : GrabberSource by rig.source {
                override val canRelax: Boolean = true

                override fun knownMode(device: LensInfo): KnownMode = KnownMode.Listed(1920 to 1080)

                override fun create(device: LensInfo, size: Pair<Int, Int>): FrameGrabber {
                    asked += size
                    return rig.source.create(device, constrained = true)
                }
            },
            authorization = { PermissionStatus.Granted },
        )
        try {
            val start = async { sut.start(desired) }
            rig.settle()

            assertEquals(CameraKResult.Success(Unit), start.await())
            assertEquals(listOf(1920 to 1080), asked, "the sizes asked for")
            assertEquals(1, rig.camera.startCount, "the camera's opens")
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

@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

/**
 * `selectCamera(ById)` on an open desktop engine moves the grab loop to that enumerated device and
 * returns once its first frame arrives, with the new camera reported; an id the engine did not
 * enumerate is NoCamera and leaves the open camera as it was (§2.4 desktop, §4.1 row 2). A device
 * that does not open returns its open error, and the engine reopens the camera it had. A device that
 * opens but delivers no frame within the engine's no-frame bound is given up: the engine reopens the
 * camera it had and stays Open, and the switch is `Timeout(LENS)`, or `Timeout(DEVICE_TYPE)` when only
 * the device type would have changed (D2' carry-over 3, Decision 7).
 */
class SelectCameraByIdTest {
    private val first = webcam("usb-first")
    private val second = webcam("usb-second")

    @Test
    fun opensTheGrabberForThatId() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val sut = rig.engine(enumerate = { CameraCapabilities(listOf(first, second)) })
        try {
            open(rig, sut, CameraSelection.ById(first.id))
            assertEquals(first.id, rig.openedDeviceId)

            val switched = async { sut.selectCamera(CameraSelection.ById(second.id)) }
            rig.settle()

            assertEquals(CameraKResult.Success(second), switched.await())
            assertEquals(second.id, rig.openedDeviceId, "the device the grabber was opened for")
            assertEquals(second, sut.hardware.value.camera, "the reported camera")
            assertEquals(EngineState.Open, sut.state.value)
            assertEquals(2, rig.camera.startCount, "grabber starts")
            assertEquals(1, rig.camera.releaseCount, "the first device's grabber was not released")
        } finally {
            sut.close()
        }
    }

    @Test
    fun unknownIdIsNoCamera() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val sut = rig.engine(enumerate = { CameraCapabilities(listOf(first, second)) })
        try {
            open(rig, sut, CameraSelection.ById(first.id))

            val result = sut.selectCamera(CameraSelection.ById("not-a-device"))
            rig.settle()

            assertIs<CameraKError.NoCamera>(assertIs<CameraKResult.Failure>(result).error)
            assertEquals(EngineState.Open, sut.state.value, "a failed selection changed the state")
            assertEquals(first, sut.hardware.value.camera, "a failed selection changed the camera")
            assertEquals(1, rig.camera.startCount, "a failed selection reopened the camera")
            assertEquals(0, rig.camera.releaseCount)
        } finally {
            sut.close()
        }
    }

    @Test
    fun aDeviceThatDoesNotOpenReturnsItsErrorAndThePreviousCameraReopens() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val refused = FrameGrabber.Exception("avformat_open_input() error -5: Could not open input \"1\".")
        val source = object : GrabberSource {
            override fun deviceKey(device: LensInfo): Any = device.id

            override val canRelax: Boolean = false

            override fun create(device: LensInfo, constrained: Boolean): FrameGrabber =
                if (device.id == second.id) RefusesToOpen(refused) else rig.camera
        }
        val sut = DesktopCameraEngine(
            CameraConfiguration(),
            rig.environment(),
            source,
            authorization = { PermissionStatus.Granted },
            enumerate = { CameraCapabilities(listOf(first, second)) },
        )
        try {
            open(rig, sut, CameraSelection.ById(first.id))

            val switched = async { sut.selectCamera(CameraSelection.ById(second.id)) }
            rig.settle()

            val error = assertIs<CameraKError.Hardware>(assertIs<CameraKResult.Failure>(switched.await()).error)
            assertEquals(-5, error.platformCode)
            assertEquals(EngineState.Open, sut.state.value, "the engine after a switch that failed")
            assertEquals(first, sut.hardware.value.camera, "the camera after a switch that failed")
            assertEquals(2, rig.camera.startCount, "the previous camera was not reopened")
        } finally {
            sut.close()
        }
    }

    @Test
    fun aDeviceThatOpensButDeliversNoFrameRevertsAfterNoFrameTimeoutAndIsTimeoutLens() = runTest {
        val silent = webcam("usb-silent", CameraLens.FRONT)
        assertSilentSwitchReverts(silent, CameraOperation.LENS)
    }

    @Test
    fun aSilentDeviceOfTheSameLensIsTimeoutDeviceType() = runTest {
        val silent = webcam("usb-wide", CameraLens.BACK, CameraDeviceType.WIDE_ANGLE)
        assertSilentSwitchReverts(silent, CameraOperation.DEVICE_TYPE)
    }

    // A switch from [first] to [silent], which opens and then delivers nothing, ends as Timeout([operation]).
    private suspend fun TestScope.assertSilentSwitchReverts(silent: LensInfo, operation: CameraOperation) {
        val rig = DesktopEngineRig(testScheduler)
        val silentCamera = rig.newCamera().apply { holdOpen = true }
        val source = object : GrabberSource {
            override fun deviceKey(device: LensInfo): Any = device.id

            override val canRelax: Boolean = false

            override fun create(device: LensInfo, constrained: Boolean): FrameGrabber =
                if (device.id == silent.id) silentCamera else rig.camera
        }
        val sut = DesktopCameraEngine(
            CameraConfiguration(),
            rig.environment(),
            source,
            authorization = { PermissionStatus.Granted },
            enumerate = { CameraCapabilities(listOf(first, silent)) },
        )
        try {
            open(rig, sut, CameraSelection.ById(first.id))

            val switched = async { sut.selectCamera(CameraSelection.ById(silent.id)) }
            rig.settle()
            assertEquals(1, silentCamera.startCount, "the switch did not open the other device")
            assertFalse(switched.isCompleted, "the switch returned before the other device's first frame")

            advanceTimeBy(NO_FRAME_TIMEOUT - 1.milliseconds)
            runCurrent()
            rig.settle()
            assertFalse(switched.isCompleted, "the switch gave up before the no-frame timeout")

            advanceTimeBy(1.milliseconds)
            runCurrent()
            rig.settle()

            val error = assertIs<CameraKError.Timeout>(assertIs<CameraKResult.Failure>(switched.await()).error)
            assertEquals(operation, error.operation)
            assertEquals(EngineState.Open, sut.state.value, "the engine after a switch to a silent device")
            assertEquals(first, sut.hardware.value.camera, "the camera after a switch to a silent device")
            assertEquals(2, rig.camera.startCount, "the previous camera was not reopened")
            assertEquals(1, silentCamera.releaseCount, "the silent device was not released")
        } finally {
            sut.close()
            rig.settle()
        }
    }

    private class RefusesToOpen(private val failure: Exception) : FrameGrabber() {
        override fun start(): Unit = throw failure

        override fun stop() = Unit

        override fun trigger() = Unit

        override fun grab(): Frame? = null

        override fun release() = Unit
    }

    private suspend fun TestScope.open(rig: DesktopEngineRig, sut: DesktopCameraEngine, selection: CameraSelection) {
        val start = async { sut.start(desiredOn(selection)) }
        rig.settle()
        assertEquals(CameraKResult.Success(Unit), start.await())
    }

    private fun desiredOn(selection: CameraSelection) = DesiredHardware(
        selection = selection,
        flashMode = FlashMode.OFF,
        torchMode = TorchMode.OFF,
        zoomRatio = 1f,
        targetOrientation = null,
    )

    private companion object {
        fun webcam(id: String, lens: CameraLens = CameraLens.BACK, type: CameraDeviceType = CameraDeviceType.DEFAULT) =
            LensInfo(id, type, lens, 1f, 1f, hasFlash = false, isLogical = false)
    }
}

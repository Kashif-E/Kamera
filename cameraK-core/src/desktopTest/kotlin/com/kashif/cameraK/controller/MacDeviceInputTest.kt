@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.desktopEngine
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * FFmpeg's avfoundation input opens a camera by index or by localized name, not by `uniqueID`. At
 * every open the engine re-reads the cameras through the Objective-C runtime and opens the
 * `uniqueID`'s current name, by index (in `devicesWithMediaType:` order) only when two cameras
 * share that name. A `uniqueID` that is gone at open is NoCamera (§2.4 desktop).
 */
class MacDeviceInputTest {
    @Test
    fun opensByLocalizedNameAndByIndexOnlyWhenNamesCollide() {
        val runtime = FakeMacCameras(
            listOf(
                FakeMacCameras.Device("built-in", "FaceTime HD Camera", 0, FakeMacCameras.BUILT_IN),
                FakeMacCameras.Device("usb-1", "USB Camera", 0, FakeMacCameras.EXTERNAL),
                FakeMacCameras.Device("usb-2", "USB Camera", 0, FakeMacCameras.EXTERNAL),
            ),
        )
        val mac = MacDevices({ runtime })

        assertEquals(DeviceInput("FaceTime HD Camera", "avfoundation"), mac.input("built-in"))
        assertEquals(DeviceInput("1", "avfoundation"), mac.input("usb-1"))
        assertEquals(DeviceInput("2", "avfoundation"), mac.input("usb-2"))

        // Re-read at every open: once the second USB camera is gone, the first has a unique name.
        runtime.devices = runtime.devices.take(2)
        assertEquals(DeviceInput("USB Camera", "avfoundation"), mac.input("usb-1"))
    }

    @Test
    fun aUniqueIdGoneAtOpenIsNoCamera() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val runtime = FakeMacCameras(
            listOf(
                FakeMacCameras.Device("built-in", "FaceTime HD Camera", 2, FakeMacCameras.BUILT_IN),
                FakeMacCameras.Device("usb", "USB Camera", 0, FakeMacCameras.EXTERNAL),
            ),
        )
        val opened = CopyOnWriteArrayList<DeviceInput>()
        val sut = desktopEngine(
            config = CameraConfiguration(),
            context = CameraKPlatformContext(),
            environment = rig.environment(),
            os = OperatingSystem.MAC,
            devices = MacDevices(
                { runtime },
                openInput = { input, _ ->
                    opened += input
                    rig.camera
                },
            ),
        )
        try {
            runCurrent()
            assertEquals(listOf("built-in", "usb"), sut.capabilities.value.allLenses.map { it.id })

            runtime.devices = runtime.devices.take(1)
            val start = async { sut.start(desiredOn(CameraSelection.ById("usb"))) }
            rig.settle()

            val error = assertIs<CameraKResult.Failure>(start.await()).error
            assertIs<CameraKError.NoCamera>(error, "an unplugged camera's start")
            assertIs<CameraKError.NoCamera>(assertIs<EngineState.Failed>(sut.state.value).error)
            assertEquals(emptyList(), opened.toList(), "FFmpeg was asked to open a camera that is gone")
            assertEquals(0, rig.camera.startCount)
        } finally {
            sut.close()
        }
    }

    private fun desiredOn(selection: CameraSelection) = DesiredHardware(
        selection = selection,
        flashMode = FlashMode.OFF,
        torchMode = TorchMode.OFF,
        zoomRatio = 1f,
        targetOrientation = null,
    )
}

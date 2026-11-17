@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.engine.CameraSelection
import dev.kashifmehmood.camerak.engine.DesiredHardware
import dev.kashifmehmood.camerak.enums.CameraDeviceType
import dev.kashifmehmood.camerak.enums.FlashMode
import dev.kashifmehmood.camerak.enums.TorchMode
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A configuration with no lens opens the platform default camera (design §6.1 "Default lens"; w8 G3).
 * On desktop that is the system default device, which every enumeration lists first, not the first
 * device that reports BACK: an external webcam reports BACK while the built-in camera reports FRONT,
 * and `ByLens(BACK)` would open the webcam.
 */
class SelectCameraDefaultTest {
    @Test
    fun defaultOpensTheSystemDefaultNotAnExternalCameraListedFirst() = runTest {
        // FFmpeg's order lists the webcam first; AVFoundation's default is the built-in camera.
        val mac = FakeMacCameras(
            listOf(
                FakeMacCameras.Device("usb", "USB Camera", 0, FakeMacCameras.EXTERNAL),
                FakeMacCameras.Device("built-in", "FaceTime HD Camera", 2, FakeMacCameras.BUILT_IN),
            ),
            defaultId = "built-in",
        )

        assertEquals("built-in", openedOnDefault(DeviceEnumerator(MacDevices({ mac }))))
    }

    @Test
    fun whenEnumerationFailsDefaultOpensTheDefaultEntry() = runTest {
        val withoutJna = MacDevices({ throw NoClassDefFoundError("com/sun/jna/Native") })

        assertEquals(desktopLensInfo().id, openedOnDefault(DeviceEnumerator(withoutJna)))
    }

    // The device a start on Default opens among what [enumerator] lists.
    private suspend fun TestScope.openedOnDefault(enumerator: DeviceEnumerator): String? {
        val rig = DesktopEngineRig(testScheduler)
        val sut = rig.engine(enumerate = { desktopCapabilities(enumerator.lenses()) })
        try {
            val start = async { sut.start(desiredOn(CameraSelection.Default(CameraDeviceType.DEFAULT))) }
            rig.settle()
            assertEquals(CameraKResult.Success(Unit), start.await(), "start on the default camera")
            assertEquals(rig.openedDeviceId, sut.hardware.value.camera?.id, "the reported camera")
            return rig.openedDeviceId
        } finally {
            sut.close()
            rig.settle()
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

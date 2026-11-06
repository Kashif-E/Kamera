@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capabilities.capabilitiesOf
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `ByLens(lens)` on desktop opens the first enumerated device whose `LensInfo.lens` is that lens,
 * external cameras included (they report BACK), and the first device when none matches, because
 * 1.x opened the one webcam whatever lens was configured (§2.4 desktop).
 */
class SelectCameraByLensTest {
    private val facetime = webcam("facetime", CameraLens.FRONT)
    private val usb = webcam("usb", CameraLens.BACK, isExternal = true)
    private val otherFront = webcam("other-front", CameraLens.FRONT)

    @Test
    fun firstDeviceOfThatLensElseTheFirstDevice() = runTest {
        assertEquals(usb.id, openedFor(CameraLens.BACK, facetime, usb), "BACK with an external camera")
        assertEquals(facetime.id, openedFor(CameraLens.FRONT, facetime, usb), "FRONT")
        assertEquals(facetime.id, openedFor(CameraLens.BACK, facetime, otherFront), "BACK with no BACK device")
    }

    // The device a start on ByLens([lens]) opens among [devices], listed in that order.
    private suspend fun TestScope.openedFor(lens: CameraLens, vararg devices: LensInfo): String? {
        val rig = DesktopEngineRig(testScheduler)
        val sut = rig.engine(enumerate = { capabilitiesOf(devices.toList()) })
        try {
            val start = async { sut.start(desiredOn(CameraSelection.ByLens(lens, CameraDeviceType.DEFAULT))) }
            rig.settle()
            assertEquals(CameraKResult.Success(Unit), start.await(), "start on $lens")
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

    private companion object {
        fun webcam(id: String, lens: CameraLens, isExternal: Boolean = false) = LensInfo(
            id = id,
            deviceType = CameraDeviceType.DEFAULT,
            lens = lens,
            minZoom = 1f,
            maxZoom = 1f,
            hasFlash = false,
            isLogical = false,
            isExternal = isExternal,
        )
    }
}

@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The 1.x facade's `toggleCameraLens` over an engine with several devices goes through
 * `engine.selectCamera`, moving to the next device in enumeration order and back to the first after
 * the last (D2' carry-over 2). A desktop webcam faces no particular way, so the toggle cycles the
 * devices rather than flipping a lens.
 */
class DesktopFacadeLensTest {
    private val devices = listOf(webcam("usb-a"), webcam("usb-b"), webcam("usb-c"))

    @Test
    fun toggleCameraLensCyclesTheEnginesDevicesInEnumerationOrder() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val sut = rig.engine(enumerate = { CameraCapabilities(devices) })
        val facade = sut.legacyController
        try {
            facade.startSession()
            rig.settle()
            assertEquals(EngineState.Open, sut.state.value)
            assertEquals(devices[0], sut.hardware.value.camera, "the camera the session opened on")

            val reported = (1..devices.size).map {
                facade.toggleCameraLens()
                rig.settle()
                assertEquals(EngineState.Open, sut.state.value, "the engine after toggle $it")
                assertEquals(sut.hardware.value.camera?.id, rig.openedDeviceId, "the device opened by toggle $it")
                sut.hardware.value.camera
            }

            assertEquals(listOf(devices[1], devices[2], devices[0]), reported, "the cameras after each toggle")
        } finally {
            facade.cleanup()
            rig.settle()
        }
    }

    private fun webcam(id: String) =
        LensInfo(id, CameraDeviceType.DEFAULT, CameraLens.BACK, 1f, 1f, hasFlash = false, isLogical = false)
}

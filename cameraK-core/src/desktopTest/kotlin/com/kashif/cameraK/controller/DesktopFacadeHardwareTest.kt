@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The 1.x facade's flash, torch and zoom members run on the engine's commands (§12.1, W4 decision
 * G3). The facade holds one desired hardware value: a value set before start is kept and passed to
 * the engine's start, and the capabilities it reports are the engine's enumerated devices. While the
 * engine is Open a setter also runs the engine's command; the desktop commands change no hardware,
 * so what shows that they ran is the facade's log of a refused value (OFF and zoom 1 are accepted).
 */
class DesktopFacadeHardwareTest {
    @Test
    fun whileOpenTheFacadesSettersGoThroughTheEngine() = runTest {
        val refusals = CopyOnWriteArrayList<String>()
        val wasEnabled = CameraKLogger.enabled
        val previousSink = CameraKLogger.sink
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, _ ->
            if (level == CameraKLogger.Level.DEBUG && message == REFUSED) refusals += message
        }
        val rig = DesktopEngineRig(testScheduler)
        val sut = rig.engine()
        val facade = sut.legacyController
        try {
            facade.setFlashMode(FlashMode.ON)
            facade.setTorchMode(TorchMode.ON)
            facade.setZoom(2f)
            assertEquals(0, refusals.size, "refusals before Open: the engine was called before it opened")

            facade.startSession()
            rig.settle()
            assertEquals(EngineState.Open, sut.state.value)

            facade.setFlashMode(FlashMode.OFF)
            facade.setTorchMode(TorchMode.OFF)
            facade.setZoom(1f)
            assertEquals(0, refusals.size, "refusals of OFF and zoom 1")

            facade.setFlashMode(FlashMode.ON)
            assertEquals(1, refusals.size, "refusals after setFlashMode(ON) while Open")
            facade.setFlashMode(FlashMode.AUTO)
            assertEquals(2, refusals.size, "refusals after setFlashMode(AUTO) while Open")
            facade.setTorchMode(TorchMode.ON)
            assertEquals(3, refusals.size, "refusals after setTorchMode(ON) while Open")
            facade.setZoom(2f)
            assertEquals(4, refusals.size, "refusals after setZoom(2) while Open")
            assertEquals(EngineState.Open, sut.state.value)
        } finally {
            facade.cleanup()
            rig.settle()
            CameraKLogger.sink = previousSink
            CameraKLogger.enabled = wasEnabled
        }
    }

    @Test
    fun torchAndZoomSetBeforeStartAreAppliedAtStart() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val sut = rig.engine()
        val facade = sut.legacyController
        try {
            facade.setFlashMode(FlashMode.AUTO)
            facade.setTorchMode(TorchMode.ON)
            facade.setZoom(2f)
            facade.setTargetOrientation(DeviceOrientation.LANDSCAPE_LEFT)
            assertEquals(EngineState.Closed, sut.state.value, "a setter started the engine")

            facade.startSession()
            rig.settle()

            assertEquals(EngineState.Open, sut.state.value)
            assertEquals(
                DesiredHardware(
                    selection = CameraSelection.ByLens(CameraLens.BACK, CameraDeviceType.DEFAULT),
                    flashMode = FlashMode.AUTO,
                    torchMode = TorchMode.ON,
                    zoomRatio = 2f,
                    targetOrientation = DeviceOrientation.LANDSCAPE_LEFT,
                ),
                sut.startedWith,
                "what the facade's start passed to the engine",
            )
        } finally {
            facade.cleanup()
            rig.settle()
        }
    }

    @Test
    fun facadeCapabilitiesAreTheEnginesDevices() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val devices = listOf(webcam("usb-a"), webcam("usb-b"))
        val sut = rig.engine(enumerate = { CameraCapabilities(devices) })
        try {
            runCurrent()

            assertEquals(devices, sut.legacyController.getCameraCapabilities().allLenses)
        } finally {
            sut.close()
        }
    }

    private fun webcam(id: String) =
        LensInfo(id, CameraDeviceType.DEFAULT, CameraLens.BACK, 1f, 1f, hasFlash = false, isLogical = false)

    private companion object {
        const val REFUSED = "CameraK: The desktop camera has no such setting; it keeps OFF and zoom 1"
    }
}

@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.utils.CameraKEngineApi
import platform.AVFoundation.AVCaptureTorchModeOn
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The 1.x facade keeps one desired flash, torch and zoom (w4 decision G3). Set before the session
 * starts, they are not sent to the engine, which answers hardware commands NotReady until Open; the
 * facade's startSession passes them to the engine's start, which applies them to the camera. Set
 * while Open, they go through the engine's commands.
 */
class FacadeDesiredHardwareTest {
    private val driver = AppleEngineDriver(adjustableBack = true)

    @Test
    fun torchAndZoomSetBeforeStartAreAppliedAtStart() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        val facade = driver.engine.legacyController

        facade.setTorchMode(TorchMode.ON)
        facade.setZoom(2f)
        driver.settle()
        assertEquals(TorchMode.ON, facade.getTorchMode(), "the torch the facade keeps before start")
        assertEquals(2f, facade.getZoom(), "the zoom the facade keeps before start")

        facade.startSession()
        driver.settle()
        assertEquals(EngineState.Open, engine.state.value)

        assertEquals(AVCaptureTorchModeOn, driver.back.lastTorchSet, "the torch set before start, at start")
        assertEquals(2.0, driver.back.lastZoomSet, "the zoom set before start, at start")
        engine.close()
    }

    @Test
    fun whileOpenTheFacadesSettersGoThroughTheEngine() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        val facade = driver.engine.legacyController
        facade.startSession()
        driver.settle()
        assertEquals(EngineState.Open, engine.state.value)

        facade.setZoom(9f)
        facade.setTorchMode(TorchMode.ON)
        driver.settle()

        assertEquals(4.0, driver.back.lastZoomSet, "the facade's zoom, clamped into the camera's range by the engine")
        assertEquals(emptyList(), driver.back.outOfRangeZoomSets, "the device was given a zoom outside its range")
        assertEquals(AVCaptureTorchModeOn, driver.back.lastTorchSet, "the facade's torch")
        assertEquals(4f, facade.getZoom(), "the facade's zoom after the engine clamped it")
        engine.close()
    }
}

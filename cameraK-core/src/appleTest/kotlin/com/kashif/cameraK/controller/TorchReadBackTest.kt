@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import platform.AVFoundation.AVCaptureTorchModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The engine's torch is what the device reports through key-value observing, never the requested
 * mode: a torch the system turns off for heat is reported off while its mode is still on.
 */
class TorchReadBackTest {
    private val driver = AppleEngineDriver(adjustableBack = true)

    @Test
    fun torchFollowsDeviceKvo() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        assertEquals(TorchMode.OFF, engine.hardware.value.torchMode)

        assertEquals(CameraKResult.Success(TorchMode.ON), engine.setTorchMode(TorchMode.ON))
        driver.settle()
        assertEquals(TorchMode.OFF, engine.hardware.value.torchMode, "the torch moved before the device reported it")

        driver.back.report(torch = AVCaptureTorchModeOn)
        driver.settle()
        assertEquals(TorchMode.ON, engine.hardware.value.torchMode, "the device's report of a lit torch")

        driver.back.report(torch = AVCaptureTorchModeOff)
        driver.settle()
        assertEquals(TorchMode.OFF, engine.hardware.value.torchMode, "the device's report of a dark torch")
        engine.close()
    }

    @Test
    fun thermalShutoffIsReported() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        assertEquals(CameraKResult.Success(TorchMode.ON), engine.setTorchMode(TorchMode.ON))
        driver.back.report(torch = AVCaptureTorchModeOn)
        driver.settle()
        assertEquals(TorchMode.ON, engine.hardware.value.torchMode)

        driver.back.thermalShutoff()
        driver.settle()

        assertEquals(AVCaptureTorchModeOn, driver.back.torchMode, "the shut-off torch keeps its mode")
        assertEquals(TorchMode.OFF, engine.hardware.value.torchMode, "a torch the system shut off is reported off")
        engine.close()
    }

    @Test
    fun aTorchTheSystemTurnsOffOnceTheSessionRunsIsTurnedOnAgain() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        assertEquals(CameraKResult.Success(TorchMode.ON), engine.setTorchMode(TorchMode.ON))
        driver.back.report(torch = AVCaptureTorchModeOn)
        driver.settle()

        // A session whose startRunning returned early starts for real later, and the system turns
        // the torch it was given off without the engine asking.
        driver.back.report(torch = AVCaptureTorchModeOff)
        driver.settle()

        assertEquals(2, driver.back.torchOnSets(), "the torch turned off behind the engine's back was set on again")
        driver.back.report(torch = AVCaptureTorchModeOn)
        driver.settle()
        assertEquals(TorchMode.ON, engine.hardware.value.torchMode)
        engine.close()
    }

    @Test
    fun aTorchTheSystemKeepsTurningOffIsSetOnAgainOnlyOnce() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        assertEquals(CameraKResult.Success(TorchMode.ON), engine.setTorchMode(TorchMode.ON))
        driver.back.report(torch = AVCaptureTorchModeOn)
        driver.settle()

        repeat(3) {
            driver.back.report(torch = AVCaptureTorchModeOff)
            driver.settle()
        }

        assertEquals(2, driver.back.torchOnSets(), "a torch that never comes back on is not set on in a loop")
        assertEquals(TorchMode.OFF, engine.hardware.value.torchMode)
        engine.close()
    }

    @Test
    fun aTorchTurnedOffOnRequestStaysOff() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        assertEquals(CameraKResult.Success(TorchMode.ON), engine.setTorchMode(TorchMode.ON))
        driver.back.report(torch = AVCaptureTorchModeOn)
        driver.settle()
        assertEquals(CameraKResult.Success(TorchMode.OFF), engine.setTorchMode(TorchMode.OFF))
        driver.back.report(torch = AVCaptureTorchModeOff)
        driver.settle()

        assertEquals(1, driver.back.torchOnSets())
        assertEquals(TorchMode.OFF, engine.hardware.value.torchMode)
        engine.close()
    }

    private fun FakeDevice.torchOnSets(): Int =
        calls.count { it.op == "setTorchMode" && it.value == AVCaptureTorchModeOn }
}

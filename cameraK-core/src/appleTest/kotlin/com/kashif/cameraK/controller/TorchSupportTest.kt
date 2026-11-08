@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import platform.AVFoundation.AVCaptureTorchModeOn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The engine's setTorchMode asks the bound camera's device before it sets anything: a device without a
 * torch, or a torch mode the device does not support, is `Unsupported(TORCH)`, and the device is never
 * given the mode, because AVFoundation raises an exception nothing can catch for it
 * (ios-device-lock-unchecked).
 */
class TorchSupportTest {
    @Test
    fun aModeTheTorchDoesNotSupportIsUnsupportedAndNeverSet() {
        val driver = AppleEngineDriver(adjustableBack = true) // the back torch supports off and on
        driver.runCase {
            val engine = driver.createEngine()
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

            val refused = assertIs<CameraKResult.Failure>(engine.setTorchMode(TorchMode.AUTO), "AUTO on this torch")
            assertEquals(CameraFeature.TORCH, assertIs<CameraKError.Unsupported>(refused.error).feature)
            assertEquals(emptyList(), driver.back.unsupportedTorchSets, "the device was given a mode it lacks")

            assertEquals(CameraKResult.Success(TorchMode.ON), engine.setTorchMode(TorchMode.ON), "a mode it has")
            assertEquals(AVCaptureTorchModeOn, driver.back.lastTorchSet)
            engine.close()
        }
    }

    @Test
    fun aCameraWithoutATorchRefusesItAndIsNeverGivenAMode() {
        val driver = AppleEngineDriver() // neither camera has a torch
        driver.runCase {
            val engine = driver.createEngine()
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

            for (mode in listOf(TorchMode.ON, TorchMode.AUTO)) {
                val refused = assertIs<CameraKResult.Failure>(engine.setTorchMode(mode), "$mode without a torch")
                assertEquals(CameraFeature.TORCH, assertIs<CameraKError.Unsupported>(refused.error).feature)
            }
            assertEquals(emptyList(), driver.back.unsupportedTorchSets, "a device without a torch was given a mode")
            engine.close()
        }
    }

    // On an iPhone 14 Pro the front camera, which has no torch, refused OFF with Unsupported(TORCH) though
    // its capabilities list OFF (device matrix #18). A torch that does not exist is off.
    @Test
    fun offAlwaysSucceedsAndACameraWithoutATorchIsNeverGivenIt() {
        val driver = AppleEngineDriver() // neither camera has a torch
        driver.runCase {
            val engine = driver.createEngine()
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

            assertEquals(
                CameraKResult.Success(TorchMode.OFF),
                engine.setTorchMode(TorchMode.OFF),
                "OFF without a torch",
            )
            assertEquals(null, driver.back.lastTorchSet, "a device without a torch was given a mode")
            driver.settle()
            assertEquals(TorchMode.OFF, engine.hardware.value.torchMode, "the torch the engine reports")
            engine.close()
        }
    }
}

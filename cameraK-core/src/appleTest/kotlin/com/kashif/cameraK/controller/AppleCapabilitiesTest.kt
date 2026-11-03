@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The engine enumerates its cameras on its own scope after construction, never inside the
 * constructor, and fills the first real capabilities: the 1.x enumerator's lenses, flash and torch
 * from hasFlash, and pause and resume from iOS 18, where AVCaptureMovieFileOutput can pause.
 */
class AppleCapabilitiesTest {
    @Test
    fun enumeratedOnTheEngineScopeNotInTheConstructor() {
        val driver = AppleEngineDriver()
        driver.runCase {
            val engine = driver.createEngine()
            assertEquals(0, driver.enumerationsRun, "the constructor enumerated the cameras")
            assertEquals(CameraCapabilities.EMPTY, engine.capabilities.value, "capabilities before enumeration")

            driver.settle()

            assertEquals(1, driver.enumerationsRun, "enumerations after the engine's scope ran")
            val capabilities = engine.capabilities.value
            assertEquals(driver.lenses, capabilities.allLenses)
            assertEquals(setOf(FlashMode.OFF), capabilities.supportedFlashModes(AppleEngineDriver.BACK_ID))
            assertEquals(setOf(TorchMode.OFF), capabilities.supportedTorchModes(AppleEngineDriver.BACK_ID))
            engine.close()
        }
    }

    @Test
    fun pauseResumeOnlyFromIos18() {
        for ((os, supported) in listOf(17 to false, 18 to true)) {
            val driver = AppleEngineDriver(osVersion = os)
            driver.runCase {
                val engine = driver.createEngine()
                driver.settle()
                val pause = engine.capabilities.value.supportsPauseResume
                if (supported) {
                    assertTrue(pause, "iOS $os can pause a recording")
                } else {
                    assertFalse(pause, "iOS $os cannot pause a recording")
                }
                engine.close()
            }
        }
    }
}

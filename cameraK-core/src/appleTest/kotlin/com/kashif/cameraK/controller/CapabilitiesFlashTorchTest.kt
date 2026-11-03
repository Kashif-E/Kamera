@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import platform.AVFoundation.AVCaptureFlashModeOff
import platform.AVFoundation.AVCaptureFlashModeOn
import platform.AVFoundation.AVCaptureTorchModeAuto
import platform.AVFoundation.AVCaptureTorchModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A camera's torch modes are the ones its device supports (isTorchModeSupported), and its flash modes,
 * once it is bound, are the photo output's supportedFlashModes with it behind the output; before the
 * first bind the flash comes from the device's hasFlash.
 */
class CapabilitiesFlashTorchTest {
    private val driver = AppleEngineDriver(
        adjustableBack = true,
        backTorchModes = setOf(AVCaptureTorchModeOff, AVCaptureTorchModeOn, AVCaptureTorchModeAuto),
    )

    @Test
    fun flashFromPhotoOutputSupportedFlashModesTorchFromDevice() = driver.runCase {
        driver.backPhotoFlashModes = listOf(AVCaptureFlashModeOff, AVCaptureFlashModeOn)
        val engine = driver.createEngine()
        driver.settle()
        val enumerated = engine.capabilities.value
        assertEquals(
            setOf(TorchMode.OFF, TorchMode.ON, TorchMode.AUTO),
            enumerated.supportedTorchModes(AppleEngineDriver.BACK_ID),
            "the back camera's torch modes are its device's",
        )
        assertEquals(setOf(TorchMode.OFF), enumerated.supportedTorchModes(AppleEngineDriver.FRONT_ID))

        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

        val bound = engine.capabilities.value
        assertEquals(
            setOf(FlashMode.OFF, FlashMode.ON),
            bound.supportedFlashModes(AppleEngineDriver.BACK_ID),
            "the bound camera's flash modes are the photo output's",
        )
        assertEquals(setOf(FlashMode.OFF), bound.supportedFlashModes(AppleEngineDriver.FRONT_ID))
        assertEquals(
            setOf(TorchMode.OFF, TorchMode.ON, TorchMode.AUTO),
            bound.supportedTorchModes(AppleEngineDriver.BACK_ID),
        )
        engine.close()
    }
}

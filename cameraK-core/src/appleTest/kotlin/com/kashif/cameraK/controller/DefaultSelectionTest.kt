@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlin.test.Test
import kotlin.test.assertEquals

/** A configuration with no lens starts on Default, which on iOS opens the BACK camera (design §6.1 "Default lens"). */
class DefaultSelectionTest {
    private val driver = AppleEngineDriver()

    @Test
    fun defaultSelectionOpensTheBackCamera() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()

        val start = engine.start(backCamera(CameraSelection.Default(CameraDeviceType.DEFAULT)))
        driver.settle()

        assertEquals(CameraKResult.Success(Unit), start, "a start on the default camera")
        assertEquals(
            AppleEngineDriver.BACK_ID,
            engine.hardware.value.camera?.id,
            "the default camera is not the back one",
        )
        engine.close()
    }
}

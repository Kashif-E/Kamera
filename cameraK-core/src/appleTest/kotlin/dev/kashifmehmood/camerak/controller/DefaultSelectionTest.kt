@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.engine.CameraSelection
import dev.kashifmehmood.camerak.engine.contract.AppleEngineDriver
import dev.kashifmehmood.camerak.enums.CameraDeviceType
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
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

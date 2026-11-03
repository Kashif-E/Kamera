@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import platform.AVFoundation.AVCaptureFlashModeOff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Flash has no observer on iOS: the engine validates the mode against the photo output's
 * supportedFlashModes on the session queue, stores it, and publishes and returns what the output will
 * use, read back before setFlashMode returns (contract rule 5).
 */
class FlashReadBackTest {
    private val driver = AppleEngineDriver(adjustableBack = true)

    @Test
    fun flashIsTheValidatedModeReadBackOnTheSessionQueue() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        driver.photoCapturer.clearFlashModeReads()

        assertEquals(CameraKResult.Success(FlashMode.ON), engine.setFlashMode(FlashMode.ON))
        assertEquals(FlashMode.ON, engine.hardware.value.flashMode, "the read-back was not published before return")
        val reads = driver.photoCapturer.flashModeReads
        assertTrue(reads.isNotEmpty(), "the mode was never validated against the photo output")
        assertEquals(setOf(SESSION_QUEUE), reads.toSet(), "the photo output's flash modes were read off the queue")

        // The output stops offering a flash after the capabilities were read: its read-back wins.
        driver.backPhotoFlashModes = listOf(AVCaptureFlashModeOff)
        assertEquals(
            CameraKResult.Success(FlashMode.OFF),
            engine.setFlashMode(FlashMode.AUTO),
            "setFlashMode returns the platform's read-back, not the request",
        )
        assertEquals(FlashMode.OFF, engine.hardware.value.flashMode, "hardware holds the read-back, not the request")
        engine.close()
    }

    private companion object {
        const val SESSION_QUEUE = "com.kashif.cameraK.session"
    }
}

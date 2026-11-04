@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.ZoomState
import com.kashif.cameraK.utils.CameraKEngineApi
import platform.AVFoundation.AVCaptureFlashModeOff
import platform.AVFoundation.AVCaptureFlashModeOn
import platform.AVFoundation.AVCaptureTorchModeOn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A camera switch binds the new camera's hardware as a start does (design §6.2): its flash modes are
 * read from the photo output with it behind the output, its torch and zoom are observed in place of the
 * previous camera's, and the desired zoom is applied to it, clamped into its range.
 */
class LensSwitchHardwareTest {
    private val driver = AppleEngineDriver(adjustableBack = true, adjustableFront = true)

    @Test
    fun theNewCamerasFlashModesAreThePhotoOutputsOnceItIsBound() = driver.runCase {
        driver.frontPhotoFlashModes = listOf(AVCaptureFlashModeOff, AVCaptureFlashModeOn)
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        assertEquals(
            setOf(FlashMode.OFF, FlashMode.AUTO, FlashMode.ON),
            engine.capabilities.value.supportedFlashModes(AppleEngineDriver.FRONT_ID),
            "the front camera's flash modes before it is bound, from its hasFlash",
        )

        assertIs<CameraKResult.Success<*>>(engine.selectCamera(CameraSelection.ById(AppleEngineDriver.FRONT_ID)))

        assertEquals(
            setOf(FlashMode.OFF, FlashMode.ON),
            engine.capabilities.value.supportedFlashModes(AppleEngineDriver.FRONT_ID),
            "the front camera's flash modes after the switch, from the photo output",
        )
        engine.close()
    }

    @Test
    fun theNewCamerasTorchAndZoomReportsReachHardware() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        assertEquals(1, driver.back.hardwareObservers, "the started camera is observed")

        assertIs<CameraKResult.Success<*>>(engine.selectCamera(CameraSelection.ById(AppleEngineDriver.FRONT_ID)))
        assertEquals(0, driver.back.hardwareObservers, "the camera switched away from is still observed")
        assertEquals(1, driver.front.hardwareObservers, "the new camera is not observed")

        driver.front.report(torch = AVCaptureTorchModeOn)
        driver.settle()
        assertEquals(TorchMode.ON, engine.hardware.value.torchMode, "the new camera's torch report")

        driver.front.report(zoom = 1.5)
        driver.settle()
        assertEquals(ZoomState(1.5f, 1f, 2f), engine.zoom.value, "the new camera's zoom report")
        engine.close()
    }

    @Test
    fun theDesiredZoomIsAppliedClampedIntoTheNewCamerasRange() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        assertEquals(CameraKResult.Success(ZoomState(3f, 1f, 4f)), engine.setZoom(3f))

        assertIs<CameraKResult.Success<*>>(engine.selectCamera(CameraSelection.ById(AppleEngineDriver.FRONT_ID)))
        assertEquals(2.0, driver.front.lastZoomSet, "the desired zoom, clamped into the front camera's range")
        assertEquals(emptyList(), driver.front.outOfRangeZoomSets, "the front camera was given a zoom out of range")

        assertIs<CameraKResult.Success<*>>(engine.selectCamera(CameraSelection.ById(AppleEngineDriver.BACK_ID)))
        assertEquals(3.0, driver.back.lastZoomSet, "the desired zoom, back on the first camera")
        engine.close()
    }
}

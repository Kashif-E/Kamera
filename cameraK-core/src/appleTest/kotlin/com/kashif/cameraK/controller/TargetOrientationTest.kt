@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeRight
import platform.AVFoundation.AVCaptureVideoOrientationPortraitUpsideDown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The target orientation is one value the engine owns (design §2.4). Its photos and movies read it,
 * and `hardware.targetOrientation` reports it when the command returns. It is a configuration value, not a hardware
 * command: before Open it is stored and applied at start, and only a closed engine refuses it
 * (w4 decision G2).
 */
class TargetOrientationTest {
    private val driver = AppleEngineDriver(adjustableBack = true)

    private fun backCameraFacing(target: DeviceOrientation?) = DesiredHardware(
        selection = backCamera().selection,
        flashMode = backCamera().flashMode,
        torchMode = backCamera().torchMode,
        zoomRatio = backCamera().zoomRatio,
        targetOrientation = target,
    )

    @Test
    fun engineTargetOrientsPhotosAndMovies() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

        assertEquals(CameraKResult.Success(Unit), engine.setTargetOrientation(DeviceOrientation.LANDSCAPE_LEFT))

        assertEquals(AVCaptureVideoOrientationLandscapeRight, driver.engine.captureVideoOrientation(), "movies")
        assertEquals(AVCaptureVideoOrientationLandscapeRight, driver.custom.captureOrientation(), "photos")
        engine.close()
    }

    @Test
    fun hardwareReportsTheTargetWhenTheCommandReturns() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        assertNull(engine.hardware.value.targetOrientation)

        assertEquals(CameraKResult.Success(Unit), engine.setTargetOrientation(DeviceOrientation.LANDSCAPE_LEFT))

        assertEquals(DeviceOrientation.LANDSCAPE_LEFT, engine.hardware.value.targetOrientation)
        engine.close()
    }

    @Test
    fun nullTargetFollowsTheDeviceAgain() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        val followingTheDevice = driver.custom.captureOrientation()
        assertEquals(CameraKResult.Success(Unit), engine.setTargetOrientation(DeviceOrientation.PORTRAIT_UPSIDE_DOWN))
        assertEquals(AVCaptureVideoOrientationPortraitUpsideDown, driver.custom.captureOrientation())

        assertEquals(CameraKResult.Success(Unit), engine.setTargetOrientation(null))

        assertNull(engine.hardware.value.targetOrientation, "the reported target after clearing it")
        assertEquals(followingTheDevice, driver.custom.captureOrientation(), "photos follow the device again")
        assertEquals(followingTheDevice, driver.engine.captureVideoOrientation(), "movies follow the device again")
        engine.close()
    }

    @Test
    fun targetSurvivesARestartAndALensSwitch() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        assertEquals(CameraKResult.Success(Unit), engine.setTargetOrientation(DeviceOrientation.LANDSCAPE_LEFT))

        engine.stop()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCameraFacing(DeviceOrientation.LANDSCAPE_LEFT)))
        assertEquals(DeviceOrientation.LANDSCAPE_LEFT, engine.hardware.value.targetOrientation, "after a restart")

        assertIs<CameraKResult.Success<*>>(engine.selectCamera(CameraSelection.ById(AppleEngineDriver.FRONT_ID)))
        assertEquals(DeviceOrientation.LANDSCAPE_LEFT, engine.hardware.value.targetOrientation, "after a lens switch")
        assertEquals(AVCaptureVideoOrientationLandscapeRight, driver.custom.captureOrientation(), "photos after both")
        engine.close()
    }

    @Test
    fun startSetsTheTargetFromItsDesiredHardware() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.setTargetOrientation(DeviceOrientation.LANDSCAPE_LEFT))

        assertEquals(
            CameraKResult.Success(Unit),
            engine.start(backCameraFacing(DeviceOrientation.PORTRAIT_UPSIDE_DOWN)),
        )
        assertEquals(
            DeviceOrientation.PORTRAIT_UPSIDE_DOWN,
            engine.hardware.value.targetOrientation,
            "the start's target, not the one stored before it",
        )
        assertEquals(AVCaptureVideoOrientationPortraitUpsideDown, driver.custom.captureOrientation(), "photos")

        engine.stop()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCameraFacing(null)))
        assertNull(engine.hardware.value.targetOrientation, "a start with no target clears the stored one")
        engine.close()
    }

    @Test
    fun beforeOpenIsStoredAndAppliedAtStart() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()

        assertEquals(
            CameraKResult.Success(Unit),
            engine.setTargetOrientation(DeviceOrientation.LANDSCAPE_LEFT),
            "a target set before the first start",
        )
        assertEquals(AVCaptureVideoOrientationLandscapeRight, driver.custom.captureOrientation(), "the stored target")

        assertEquals(CameraKResult.Success(Unit), engine.start(backCameraFacing(DeviceOrientation.LANDSCAPE_LEFT)))
        assertEquals(DeviceOrientation.LANDSCAPE_LEFT, engine.hardware.value.targetOrientation, "the target at start")

        engine.close()
        assertEquals(
            CameraKResult.Failure(CameraKError.Closed),
            engine.setTargetOrientation(DeviceOrientation.PORTRAIT),
            "a target set after close",
        )
    }
}

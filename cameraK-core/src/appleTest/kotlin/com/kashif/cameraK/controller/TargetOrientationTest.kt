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
 * The target orientation is one value the engine owns (design §2.4). The facade's captures and movies
 * read it, `hardware.targetOrientation` reports it when the command returns, and a target set through
 * the holder or the facade orients the same photos. It is a configuration value, not a hardware
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
    fun engineTargetOrientsTheFacadesCaptures() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        val facade = driver.engine.legacyController

        assertEquals(CameraKResult.Success(Unit), engine.setTargetOrientation(DeviceOrientation.LANDSCAPE_LEFT))

        assertEquals(AVCaptureVideoOrientationLandscapeRight, facade.effectiveVideoOrientation(), "the facade's movies")
        assertEquals(AVCaptureVideoOrientationLandscapeRight, driver.custom.captureOrientation(), "the facade's photos")
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
        val facade = driver.engine.legacyController
        val followingTheDevice = driver.custom.captureOrientation()
        assertEquals(CameraKResult.Success(Unit), engine.setTargetOrientation(DeviceOrientation.PORTRAIT_UPSIDE_DOWN))
        assertEquals(AVCaptureVideoOrientationPortraitUpsideDown, driver.custom.captureOrientation())

        assertEquals(CameraKResult.Success(Unit), engine.setTargetOrientation(null))

        assertNull(engine.hardware.value.targetOrientation, "the reported target after clearing it")
        assertEquals(followingTheDevice, driver.custom.captureOrientation(), "photos follow the device again")
        assertEquals(followingTheDevice, facade.effectiveVideoOrientation(), "movies follow the device again")
        engine.close()
    }

    @Test
    fun targetSurvivesARestartAndALensSwitch() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        val facade = driver.engine.legacyController
        facade.startSession()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.setTargetOrientation(DeviceOrientation.LANDSCAPE_LEFT))

        facade.stopSession()
        driver.settle()
        facade.startSession()
        driver.settle()
        assertEquals(DeviceOrientation.LANDSCAPE_LEFT, engine.hardware.value.targetOrientation, "after a restart")

        assertIs<CameraKResult.Success<*>>(engine.selectCamera(CameraSelection.ById(AppleEngineDriver.FRONT_ID)))
        assertEquals(DeviceOrientation.LANDSCAPE_LEFT, engine.hardware.value.targetOrientation, "after a lens switch")
        assertEquals(AVCaptureVideoOrientationLandscapeRight, driver.custom.captureOrientation(), "photos after both")
        engine.close()
    }

    @Test
    fun facadeSetterSetsTheEnginesTarget() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        val facade = driver.engine.legacyController

        facade.setTargetOrientation(DeviceOrientation.LANDSCAPE_LEFT)
        facade.startSession()
        driver.settle()
        assertEquals(
            DeviceOrientation.LANDSCAPE_LEFT,
            engine.hardware.value.targetOrientation,
            "a target the facade set before startSession",
        )

        facade.setTargetOrientation(DeviceOrientation.PORTRAIT_UPSIDE_DOWN)
        driver.settle()
        assertEquals(
            DeviceOrientation.PORTRAIT_UPSIDE_DOWN,
            engine.hardware.value.targetOrientation,
            "a target the facade set while open",
        )
        assertEquals(AVCaptureVideoOrientationPortraitUpsideDown, driver.custom.captureOrientation())
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

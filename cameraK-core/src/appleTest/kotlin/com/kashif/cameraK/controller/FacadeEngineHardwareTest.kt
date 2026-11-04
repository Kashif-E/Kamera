@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.engine.contract.HardwareChange
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import platform.AVFoundation.AVCaptureTorchModeOn
import platform.UIKit.UIDeviceOrientationDidChangeNotification
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The 1.x facade over the engine (w6 decisions, I2 carry-over): while the camera is Open its torch and
 * zoom setters are the engine's commands, so the engine's own desired hardware follows them, and its
 * getters read what the engine reads back from the platform; before Open they keep the facade's
 * desired values. The flash is validated against the camera once it opens, a fixed-zoom camera keeps
 * its only ratio, a refused device-type switch puts the facade's type back, and a closed engine that
 * was never stopped leaves no orientation observer and no orientation notifications running.
 */
class FacadeEngineHardwareTest {
    private val driver = AppleEngineDriver(adjustableBack = true)

    @Test
    fun whileOpenTheFacadesTorchAndZoomSetTheEnginesDesiredHardware() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        val facade = driver.engine.legacyController
        facade.startSession()
        driver.settle()
        assertEquals(EngineState.Open, engine.state.value)

        facade.setZoom(3f)
        facade.setTorchMode(TorchMode.ON)
        driver.settle()

        assertEquals(3f, driver.engine.desiredZoomRatio, "the engine's desired zoom after the facade's setZoom")
        assertEquals(TorchMode.ON, driver.engine.desiredTorchMode, "the engine's desired torch after the facade's")
        engine.close()
    }

    @Test
    fun aTorchTheSystemShutOffReadsOffThroughTheFacade() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        val facade = driver.engine.legacyController
        facade.startSession()
        driver.settle()
        facade.setTorchMode(TorchMode.ON)
        driver.settle()
        driver.back.report(torch = AVCaptureTorchModeOn)
        driver.settle()
        assertEquals(TorchMode.ON, facade.getTorchMode(), "the lit torch")

        driver.back.thermalShutoff()
        driver.settle()

        assertEquals(TorchMode.OFF, facade.getTorchMode(), "a torch the system shut off, through getTorchMode")
        engine.close()
    }

    @Test
    fun whileOpenTheFacadesZoomIsWhatTheDeviceReports() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        val facade = driver.engine.legacyController
        facade.startSession()
        driver.settle()

        facade.setZoom(3f)
        driver.settle()
        assertEquals(3.0, driver.back.lastZoomSet, "the device was given the zoom")
        assertEquals(1f, facade.getZoom(), "getZoom before the device reported the new zoom")

        driver.changeHardware(HardwareChange(zoomRatio = 3f))
        driver.settle()
        assertEquals(3f, facade.getZoom(), "getZoom once the device reported it")

        facade.stopSession()
        driver.settle()
        assertEquals(3f, facade.getZoom(), "getZoom after stop is the facade's desired zoom")
        engine.close()
    }

    @Test
    fun aFixedZoomCameraKeepsItsOnlyRatio() {
        val fixed = AppleEngineDriver()
        fixed.runCase {
            val engine = fixed.createEngine()
            fixed.settle()
            val facade = fixed.engine.legacyController
            facade.startSession()
            fixed.settle()
            assertEquals(EngineState.Open, engine.state.value)

            val errors = mutableListOf<String>()
            val wasEnabled = CameraKLogger.enabled
            val previousSink = CameraKLogger.sink
            CameraKLogger.sink = { level, _, message, _ ->
                if (level == CameraKLogger.Level.ERROR) synchronizedOn(errors) { errors += message }
            }
            CameraKLogger.enabled = true
            try {
                facade.setZoom(3f)
                fixed.settle()
            } finally {
                CameraKLogger.enabled = wasEnabled
                CameraKLogger.sink = previousSink
            }
            assertEquals(
                emptyList(),
                synchronizedOn(errors) {
                    errors.toList()
                },
                "the facade sent a ratio the camera lacks",
            )
            facade.stopSession()
            fixed.settle()
            assertEquals(1f, facade.getZoom(), "the zoom a fixed-zoom camera keeps")
            engine.close()
        }
    }

    @Test
    fun aFlashSetWhileTheStartIsInFlightIsValidatedWhenTheCameraOpens() {
        val noFlash = AppleEngineDriver()
        noFlash.runCase {
            val engine = noFlash.createEngine()
            noFlash.settle()
            val facade = noFlash.engine.legacyController
            noFlash.holdOpen()
            facade.startSession()
            noFlash.settle()
            assertEquals(EngineState.Opening, engine.state.value)

            facade.setFlashMode(FlashMode.ON)
            noFlash.settle()
            noFlash.deliverOpen()
            noFlash.settle()
            assertEquals(EngineState.Open, engine.state.value)
            assertEquals(FlashMode.OFF, facade.getFlashMode(), "ON on a camera without a flash, once it opened")

            facade.stopSession()
            noFlash.settle()
            assertEquals(FlashMode.OFF, facade.getFlashMode(), "the facade's desired flash after the camera opened")
            engine.close()
        }
    }

    @Test
    fun aRefusedDeviceTypeSwitchPutsTheFacadesTypeBack() {
        val back = FakeDevice(isFront = false)
        val telephoto = FakeDevice(isFront = false, uniqueId = "telephoto")
        val session = RecordingSession()
        session.refuse(telephoto.input!!)
        val custom = CustomCameraController(
            qualityPrioritization = QualityPrioritization.NONE,
            sessionFactory = { session },
            cameraDevices = { _, type -> if (type == CameraDeviceType.TELEPHOTO) telephoto else back },
        )
        val facade = CameraController(
            flashMode = FlashMode.OFF,
            torchMode = TorchMode.OFF,
            cameraLens = CameraLens.BACK,
            imageFormat = ImageFormat.JPEG,
            qualityPriority = QualityPrioritization.NONE,
            directory = Directory.DOCUMENTS,
            cameraDeviceType = CameraDeviceType.DEFAULT,
            aspectRatio = AspectRatio.RATIO_4_3,
            targetResolution = null,
            mirrorFrontCamera = false,
            customController = custom,
        )
        try {
            facade.startSession()
            awaitTrue("the session to start") { session.isRunning }
            drainSessionThenMain(custom)

            facade.setPreferredCameraDeviceType(CameraDeviceType.TELEPHOTO)
            drainSessionThenMain(custom)

            assertEquals(listOf(back.input), session.inputs, "the refused telephoto input replaced the camera")
            awaitTrue("the facade to put its device type back") {
                facade.getPreferredCameraDeviceType() == CameraDeviceType.DEFAULT
            }
        } finally {
            facade.cleanup()
            drainSessionThenMain(custom)
        }
    }

    @Test
    fun aClosedEngineThatWasNeverStoppedStopsObservingTheOrientation() {
        val observers = blockObservers(UIDeviceOrientationDidChangeNotification!!) {
            driver.runCase {
                val engine = driver.createEngine()
                driver.settle()
                assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
                driver.settle()

                engine.close()
                driver.settle()
            }
        }
        assertEquals(1, observers.queues.size, "orientation observers registered by one start")
        assertEquals(0, observers.leftBehind, "orientation observers left behind by close")
        awaitTrue("the main queue to run the notification calls") { driver.orientationNotifications.size == 2 }
        assertEquals(
            listOf(true, false),
            driver.orientationNotifications,
            "orientation notifications began by the start and ended by close",
        )
    }
}

private val errorsLock = platform.Foundation.NSLock()

private fun <T> synchronizedOn(@Suppress("UNUSED_PARAMETER") guarded: Any, block: () -> T): T {
    errorsLock.lock()
    try {
        return block()
    } finally {
        errorsLock.unlock()
    }
}

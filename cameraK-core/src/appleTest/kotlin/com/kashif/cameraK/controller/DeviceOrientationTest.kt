@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIDeviceOrientationDidChangeNotification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The engine publishes how the device is held from UIDeviceOrientationDidChangeNotification, observed
 * between start and stop; face up, face down and unknown keep the last value, as the 1.x listener did.
 * The simulator cannot be turned, so the engine reads the orientation through a seam the driver answers.
 */
class DeviceOrientationTest {
    private val driver = AppleEngineDriver()

    private fun turnTo(orientation: UIDeviceOrientation) {
        driver.deviceOrientation = orientation
        NSNotificationCenter.defaultCenter.postNotificationName(
            UIDeviceOrientationDidChangeNotification,
            UIDevice.currentDevice,
        )
    }

    @Test
    fun reportsTheDeviceOrientationWhileStarted() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

        turnTo(UIDeviceOrientation.UIDeviceOrientationLandscapeLeft)
        driver.settle()
        assertEquals(DeviceOrientation.LANDSCAPE_LEFT, engine.deviceOrientation.value)

        turnTo(UIDeviceOrientation.UIDeviceOrientationPortraitUpsideDown)
        driver.settle()
        assertEquals(DeviceOrientation.PORTRAIT_UPSIDE_DOWN, engine.deviceOrientation.value)
        engine.close()
    }

    @Test
    fun faceUpAndUnknownKeepTheLastValue() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        turnTo(UIDeviceOrientation.UIDeviceOrientationLandscapeRight)
        driver.settle()
        assertEquals(DeviceOrientation.LANDSCAPE_RIGHT, engine.deviceOrientation.value)

        for (flat in listOf(
            UIDeviceOrientation.UIDeviceOrientationFaceUp,
            UIDeviceOrientation.UIDeviceOrientationFaceDown,
            UIDeviceOrientation.UIDeviceOrientationUnknown,
        )) {
            turnTo(flat)
            driver.settle()
            assertEquals(DeviceOrientation.LANDSCAPE_RIGHT, engine.deviceOrientation.value, "after $flat")
        }
        engine.close()
    }

    @Test
    fun observedOnlyBetweenStartAndStop() {
        val observers = blockObservers(UIDeviceOrientationDidChangeNotification!!) {
            driver.runCase {
                val engine = driver.createEngine()
                driver.settle()
                turnTo(UIDeviceOrientation.UIDeviceOrientationLandscapeLeft)
                driver.settle()
                assertEquals(DeviceOrientation.PORTRAIT, engine.deviceOrientation.value, "observed before start")

                assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
                turnTo(UIDeviceOrientation.UIDeviceOrientationLandscapeRight)
                driver.settle()
                assertEquals(DeviceOrientation.LANDSCAPE_RIGHT, engine.deviceOrientation.value, "while started")

                engine.stop()
                driver.settle()
                turnTo(UIDeviceOrientation.UIDeviceOrientationPortraitUpsideDown)
                driver.settle()
                assertEquals(DeviceOrientation.LANDSCAPE_RIGHT, engine.deviceOrientation.value, "observed after stop")
                engine.close()
            }
        }
        assertEquals(1, observers.queues.size, "orientation observers registered by one start")
        assertEquals(0, observers.leftBehind, "orientation observers left behind after stop")
    }

    @Test
    fun aStartThatFailsStopsObserving() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()

        val noCamera = engine.start(backCamera(CameraSelection.ById("no-such-camera")))
        assertIs<CameraKError.NoCamera>(assertIs<CameraKResult.Failure>(noCamera).error)
        turnTo(UIDeviceOrientation.UIDeviceOrientationLandscapeLeft)
        driver.settle()
        assertEquals(
            DeviceOrientation.PORTRAIT,
            engine.deviceOrientation.value,
            "observed after a start found no camera",
        )

        driver.holdOpen()
        val start = async { engine.start(backCamera()) }
        driver.settle()
        assertEquals(DeviceOrientation.LANDSCAPE_LEFT, engine.deviceOrientation.value, "read when the start began")
        driver.failOpen()
        driver.settle()
        assertIs<CameraKResult.Failure>(start.await(), "a start whose session failed")
        turnTo(UIDeviceOrientation.UIDeviceOrientationLandscapeRight)
        driver.settle()
        assertEquals(
            DeviceOrientation.LANDSCAPE_LEFT,
            engine.deviceOrientation.value,
            "observed after a start whose session failed",
        )
        engine.close()
    }
}

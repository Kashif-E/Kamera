@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import platform.Foundation.NSLock
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIDeviceOrientationDidChangeNotification
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The engine publishes how the device is held from UIDeviceOrientationDidChangeNotification, observed
 * between start and stop; face up, face down and unknown keep the last value, as the 1.x facade's
 * listener did. The facade's getDeviceOrientation and orientation listener read the engine's value.
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
    fun facadeListenerAndGetterFollowTheEngine() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        val facade = driver.engine.legacyController
        val lock = NSLock()
        val heard = mutableListOf<DeviceOrientation>()
        facade.setOnOrientationChangedListener { orientation ->
            lock.lock()
            try {
                heard += orientation
            } finally {
                lock.unlock()
            }
        }
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

        turnTo(UIDeviceOrientation.UIDeviceOrientationLandscapeLeft)
        driver.settle()
        assertEquals(DeviceOrientation.LANDSCAPE_LEFT, facade.getDeviceOrientation(), "the facade's getter")
        assertEquals(listOf(DeviceOrientation.LANDSCAPE_LEFT), heard.toList(), "the facade's listener")

        facade.setOnOrientationChangedListener(null)
        turnTo(UIDeviceOrientation.UIDeviceOrientationLandscapeRight)
        driver.settle()
        assertEquals(DeviceOrientation.LANDSCAPE_RIGHT, facade.getDeviceOrientation())
        assertEquals(listOf(DeviceOrientation.LANDSCAPE_LEFT), heard.toList(), "a removed listener was still called")
        engine.close()
    }
}

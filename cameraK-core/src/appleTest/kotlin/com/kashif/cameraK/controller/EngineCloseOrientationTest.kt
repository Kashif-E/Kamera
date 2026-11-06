@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import platform.UIKit.UIDeviceOrientationDidChangeNotification
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A closed engine that was never stopped leaves no orientation observer and no orientation
 * notifications running. (The 1.x facade's hardware cases went with the facade in C1'.)
 */
class EngineCloseOrientationTest {
    private val driver = AppleEngineDriver(adjustableBack = true)

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

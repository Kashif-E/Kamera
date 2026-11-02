package com.kashif.cameraK.controller

import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSUUID
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A camera leaves no memory-warning observer behind. Every viewDidLoad used to add one through the
 * public MemoryManager object and nothing ever removed it, so each camera screen an app opened kept
 * another block running on every memory warning for the life of the process.
 */
class MemoryWarningObserverTest {
    @Test
    fun theCounterSeesAnObserverLeftBehindAndNotOneRemoved() {
        val name = "com.kashif.cameraK.test.probe.${NSUUID().UUIDString}"
        val center = NSNotificationCenter.defaultCenter
        var kept: Any? = null
        assertEquals(1, blockObserversLeftBehind(name) { kept = center.addObserverForName(name, null, null) { _ -> } })
        kept?.let(center::removeObserver)
        assertEquals(
            0,
            blockObserversLeftBehind(name) {
                center.removeObserver(center.addObserverForName(name, null, null) { _ -> })
            },
        )
    }

    @Test
    fun loadingAndCleaningUpCamerasLeavesNoMemoryWarningObserverBehind() {
        // The first camera screen lets UIKit register whatever it registers once per process.
        CaptureRig().run {
            start()
            tearDown()
        }

        val leftBehind = blockObserversLeftBehind(UIApplicationDidReceiveMemoryWarningNotification!!) {
            repeat(2) {
                CaptureRig().run {
                    start()
                    tearDown()
                }
            }
        }

        assertEquals(0, leftBehind, "camera screens left memory-warning observers behind")
    }
}

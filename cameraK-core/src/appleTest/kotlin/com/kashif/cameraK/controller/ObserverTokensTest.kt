@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionDidStartRunningNotification
import platform.AVFoundation.AVCaptureSessionErrorKey
import platform.AVFoundation.AVCaptureSessionInterruptionEndedNotification
import platform.AVFoundation.AVCaptureSessionRuntimeErrorNotification
import platform.AVFoundation.AVCaptureSessionWasInterruptedNotification
import platform.AVFoundation.AVErrorDeviceWasDisconnected
import platform.Foundation.NSNotificationCenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The engine observes its own session only (object = the session), and removes every observer it
 * added when it closes, so a closed engine leaves no block running on another session's events.
 */
class ObserverTokensTest {
    private val names = listOf(
        AVCaptureSessionWasInterruptedNotification!!,
        AVCaptureSessionInterruptionEndedNotification!!,
        AVCaptureSessionRuntimeErrorNotification!!,
        AVCaptureSessionDidStartRunningNotification!!,
    )

    @Test
    fun observersUseSessionObjectAndAreRemovedOnClose() {
        for (name in names) {
            val driver = AppleEngineDriver()
            val whileOpen = blockObserversLeftBehind(name) {
                driver.runCase {
                    driver.createEngine()
                    driver.settle()
                    assertEquals(CameraKResult.Success(Unit), driver.engine.start(backCamera()))
                }
            }
            assertEquals(1, whileOpen, "observers of $name while the engine is open")
            driver.engine.close()

            val afterClose = blockObserversLeftBehind(name) {
                driver.runCase {
                    val engine = driver.createEngine()
                    driver.settle()
                    assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
                    engine.close()
                    driver.settle()
                }
            }
            assertEquals(0, afterClose, "observers of $name left behind after close")
        }

        val driver = AppleEngineDriver()
        driver.runCase {
            val engine = driver.createEngine()
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            val error = mapOf<Any?, Any?>(
                AVCaptureSessionErrorKey to FakeSessionOps.avError(AVErrorDeviceWasDisconnected),
            )

            NSNotificationCenter.defaultCenter.postNotificationName(
                AVCaptureSessionRuntimeErrorNotification!!,
                AVCaptureSession(),
                error,
            )
            driver.settle()
            assertEquals(EngineState.Open, engine.state.value, "another session's runtime error reached the engine")

            driver.session.post(AVCaptureSessionRuntimeErrorNotification, error)
            driver.settle()
            assertIs<EngineState.Failed>(engine.state.value, "its own session's runtime error")
            engine.close()
        }
    }
}

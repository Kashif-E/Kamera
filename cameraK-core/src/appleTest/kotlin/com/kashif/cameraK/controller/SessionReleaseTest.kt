@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A closed engine's session holds no camera. On an iPhone 14 Pro the time to Ready rose from about
 * 320 ms to 5 s over about 330 holders of one camera created and closed in one process, until starts
 * timed out (device matrix #937); a fresh process was back to 300 ms, and opening another camera was
 * fast at once. Close only stopped the session, so each closed session kept its camera input, its
 * outputs and its preview layer's reference until Kotlin/Native's collector freed it, and every one
 * still attached to that camera slowed the next start on it. Close now takes the inputs and outputs
 * out of the session and the session out of the preview layer.
 */
class SessionReleaseTest {
    @Test
    fun aClosedEnginesSessionHasNoInputsAndNoOutputs() {
        val driver = AppleEngineDriver()
        driver.runCase {
            val engine = driver.createEngine(CameraConfiguration { frameAnalysis = true })
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            driver.settle()
            check(driver.session.inputs.isNotEmpty() && driver.session.outputs.isNotEmpty()) {
                "the started session has no camera input or no outputs"
            }

            engine.closeAndJoin()
            driver.settle()

            assertEquals(emptyList(), driver.session.inputs, "inputs left in the closed session")
            assertEquals(emptyList(), driver.session.outputs, "outputs left in the closed session")
            assertEquals(0, driver.session.openConfigurations, "close left a configuration open")
            assertEquals(false, driver.session.isRunning, "the closed session runs")
        }
    }

    @Test
    fun aClosedEnginesPreviewLayerShowsNoSession() {
        val driver = AppleEngineDriver()
        driver.runCase {
            val engine = driver.createEngine()
            val layer = engine.preview.createPreviewLayer()
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))

            engine.closeAndJoin()
            driver.settle()

            assertNull(layer.session, "the closed engine's preview layer still holds its session")
        }
    }
}

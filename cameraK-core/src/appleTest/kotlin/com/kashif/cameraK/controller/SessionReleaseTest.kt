@file:OptIn(CameraKEngineApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import platform.Foundation.NSThread
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

            closeAndJoinServicingMain(driver, engine)
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

            closeAndJoinServicingMain(driver, engine)
            driver.settle()

            assertNull(layer.session, "the closed engine's preview layer still holds its session")
        }
    }

    /**
     * Taking the session out of the preview layer opens a configuration on the session, on main, and
     * stopRunning inside a configuration raises an exception Kotlin cannot catch ("stopRunning may not
     * be called between calls to beginConfiguration and commitConfiguration"). A close off main
     * detached the layer on main while the session queue stopped the session; the detach now comes first.
     */
    @Test
    fun aCloseOffMainTakesTheSessionOutOfThePreviewBeforeItStops() {
        val driver = AppleEngineDriver()
        driver.runCase {
            val engine = driver.createEngine()
            val layer = engine.preview.createPreviewLayer()
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            driver.settle()
            val tornDown = atomic(false)
            val layerHeldTheSession = atomic(false)
            driver.session.onTearDown = {
                layerHeldTheSession.value = layer.session != null
                tornDown.value = true
            }

            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) { engine.close() }
            // Main is held: a detach queued to it cannot run yet.
            NSThread.sleepForTimeInterval(0.5)
            val tornDownWhileMainWasHeld = tornDown.value
            awaitTrue("the closed session to be torn down") { tornDown.value }

            assertFalse(tornDownWhileMainWasHeld, "the session stopped before main took it out of the preview layer")
            assertFalse(layerHeldTheSession.value, "the preview layer still held the session when it stopped")
        }
    }

    /**
     * closeAndJoin waits until the native resources are released: a holder made right after it, on the
     * same camera, must not start while the old session still runs with the camera's input (#937). With
     * a preview layer, the teardown waits for main to take the session out of the layer, and the join
     * waits for the teardown.
     */
    @Test
    fun closeAndJoinReturnsOnlyOnceTheSessionIsTornDown() {
        val driver = AppleEngineDriver()
        driver.runCase {
            val engine = driver.createEngine()
            engine.preview.createPreviewLayer()
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            driver.settle()

            val joined = CoroutineScope(Dispatchers.Default).async {
                engine.closeAndJoin()
                driver.session.inputs.size to driver.session.isRunning
            }
            // Main is held, so the detach queued to it cannot run yet.
            NSThread.sleepForTimeInterval(0.5)
            awaitTrue("closeAndJoin to return") { joined.isCompleted }

            assertEquals(
                0 to false,
                joined.getCompleted(),
                "the session's inputs and running when closeAndJoin returned",
            )
        }
    }
}

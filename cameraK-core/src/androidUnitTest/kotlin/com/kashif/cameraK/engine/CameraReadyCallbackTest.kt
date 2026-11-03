@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import androidx.camera.view.PreviewView
import com.kashif.cameraK.engine.FakeAndroidPlatform.Companion.desired
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * The facade's onCameraReady is app code. One that throws is caught and logged with the operation
 * named, as 1.x did: it escapes neither engine.start() nor, on a camera already bound, the engine's
 * scope.
 */
@RunWith(RobolectricTestRunner::class)
class CameraReadyCallbackTest {
    private val platform = FakeAndroidPlatform()
    private val errors = mutableListOf<String>()

    @After
    fun tearDown() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun aThrowingOnCameraReadyIsLoggedAndEscapesNeitherStartNorTheEngineScope() = runTest {
        val engine = platform.engine(testScheduler)
        val facade = (engine as LegacyControllerAccess).legacyController
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, _ -> if (level == CameraKLogger.Level.ERROR) errors += message }

        facade.bindCamera(PreviewView(platform.context)) {
            throw IllegalStateException("scripted onCameraReady failure")
        }
        platform.settle(testScheduler)
        val started = runCatching { engine.start(desired) }
        platform.settle(testScheduler)

        assertEquals(
            CameraKResult.Success(Unit),
            started.getOrElse { throw AssertionError("a throwing onCameraReady escaped engine.start()", it) },
        )
        assertEquals(EngineState.Open, engine.state.value)
        assertEquals(1, errors.count { "onCameraReady" in it }, "the failed callback was not logged by name: $errors")

        // On a bound camera the callback runs at once, on the engine's scope.
        facade.bindCamera(PreviewView(platform.context)) {
            throw IllegalStateException("scripted onCameraReady failure")
        }
        platform.settle(testScheduler)

        assertEquals(EngineState.Open, engine.state.value)
        assertEquals(2, errors.count { "onCameraReady" in it }, "the failed callback was not logged by name: $errors")
        engine.closeAndJoin()
    }
}

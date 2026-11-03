@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.LegacyControllerAccess
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * A controller an app or a test constructs itself (the 1.x `customGrabber` constructor) does not
 * go through the bridge, so it builds its own engine: `startSession()` starts that engine,
 * `closeAndJoin()` closes it, and the grabber is opened and released once, by the engine (§12.1).
 */
class FacadeConstructionTest {
    @Test
    fun directlyConstructedControllerOwnsItsEngine() = runBlocking {
        val camera = FakeFrameGrabber().apply { openGate() }
        val sut = CameraController(ImageFormat.JPEG, Directory.PICTURES, customGrabber = camera)
        val engine = sut.engine
        try {
            assertSame(sut, (engine as LegacyControllerAccess).legacyController, "the engine's facade is another one")
            assertEquals(EngineState.Closed, engine.state.value, "the engine opened before startSession()")

            sut.startSession()
            withTimeout(TIMEOUT_MS) { engine.state.first { it == EngineState.Open } }
            assertEquals(1, camera.startCount.get(), "grabber starts")

            withTimeout(TIMEOUT_MS) { sut.closeAndJoin() }

            assertEquals(EngineState.Closed, engine.state.value)
            assertEquals(1, camera.releaseCount.get(), "grabber releases after closeAndJoin()")
            assertEquals(emptyList(), camera.violations.toList())
        } finally {
            sut.cleanup()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}

@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.LegacyControllerAccess
import com.kashif.cameraK.engine.createPlatformEngine
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * The production constructor's engine on desktop is the real one, and `Ready.controller` reaches a
 * facade over that same engine. Nothing here starts it, so no camera is opened.
 */
class DesktopBridgeTest {
    @Test
    fun bridgeReturnsTheDesktopEngineWithItsFacade() = runBlocking {
        val dispatchers = CameraKDispatchers(main = Dispatchers.Default, default = Dispatchers.Default)
        val environment = CameraKEnvironment(dispatchers)
        val sut = createPlatformEngine(CameraConfiguration(), CameraKPlatformContext(), environment)
        try {
            val engine = assertIs<DesktopCameraEngine>(sut, "the desktop bridge still returns the 1.x adapter")
            val facade = assertIs<LegacyControllerAccess>(sut).legacyController
            assertSame(engine, facade.engine, "Ready.controller is a facade over another engine")
            assertSame(facade, assertIs<LegacyControllerAccess>(sut).legacyController, "one facade per engine")
            assertEquals(EngineState.Closed, sut.state.value)
        } finally {
            withTimeout(10_000) { sut.closeAndJoin() }
        }
    }
}

@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.coroutines.CameraKDispatchers
import dev.kashifmehmood.camerak.engine.CameraKEnvironment
import dev.kashifmehmood.camerak.engine.EngineState
import dev.kashifmehmood.camerak.engine.createPlatformEngine
import dev.kashifmehmood.camerak.platform.CameraKPlatformContext
import dev.kashifmehmood.camerak.state.CameraConfiguration
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The production constructor's engine on desktop is the real one. Nothing here starts it, so no camera is opened. */
class DesktopBridgeTest {
    @Test
    fun bridgeReturnsTheDesktopEngine() = runBlocking {
        val dispatchers = CameraKDispatchers(main = Dispatchers.Default, default = Dispatchers.Default)
        val environment = CameraKEnvironment(dispatchers)
        val sut = createPlatformEngine(CameraConfiguration(), CameraKPlatformContext(), environment)
        try {
            assertIs<DesktopCameraEngine>(sut, "the desktop bridge returns another engine")
            assertEquals(EngineState.Closed, sut.state.value)
        } finally {
            withTimeout(10_000) { sut.closeAndJoin() }
        }
    }
}

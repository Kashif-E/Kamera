@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.EngineState
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

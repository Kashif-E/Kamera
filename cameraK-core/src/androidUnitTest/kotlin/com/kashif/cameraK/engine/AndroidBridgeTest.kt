@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.controller.AndroidCameraEngine
import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertIs

/** The production constructor's engine on Android is the real one. Nothing here starts it. */
@RunWith(RobolectricTestRunner::class)
class AndroidBridgeTest {
    private val platform = FakeAndroidPlatform()

    @Test
    fun createPlatformEngineReturnsTheAndroidEngine() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        val engine = createPlatformEngine(
            CameraConfiguration(),
            CameraKPlatformContext(platform.context),
            CameraKEnvironment(dispatchers = CameraKDispatchers(main = main, default = main, io = main)),
        )

        assertIs<AndroidCameraEngine>(engine, "the bridge returns another engine")
        engine.closeAndJoin()
    }
}

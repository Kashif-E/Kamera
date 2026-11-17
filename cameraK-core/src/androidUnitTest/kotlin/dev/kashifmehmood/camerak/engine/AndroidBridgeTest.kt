@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.engine

import dev.kashifmehmood.camerak.controller.AndroidCameraEngine
import dev.kashifmehmood.camerak.coroutines.CameraKDispatchers
import dev.kashifmehmood.camerak.platform.CameraKPlatformContext
import dev.kashifmehmood.camerak.state.CameraConfiguration
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
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

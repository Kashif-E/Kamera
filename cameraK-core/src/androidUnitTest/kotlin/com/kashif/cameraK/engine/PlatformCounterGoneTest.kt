@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The holder alone counts captures in flight (pending-captures-double-decrement): the Android engine
 * passes every capture to CameraX and no longer rejects a fourth with an untyped "Burst queue full".
 * H2''s fourthInFlightCaptureIsBusy holds over the real engine.
 */
@RunWith(RobolectricTestRunner::class)
class PlatformCounterGoneTest {
    private val platform = FakeAndroidPlatform()

    @Test
    fun fourCapturesReachTheEngineAndTheHolderAloneRejectsTheFourth() = runTest {
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        val direct = List(4) { async { engine.capture() } }
        platform.settle(testScheduler)
        assertEquals(4, platform.backend.captures.size, "the engine let fewer than four captures reach CameraX")
        platform.backend.captures.forEach { it.deliverSaved(byteArrayOf(1)) }
        platform.settle(testScheduler)
        direct.forEach { assertIs<CameraKResult.Success<*>>(it.await(), "an engine capture") }
        engine.closeAndJoin()

        val main = StandardTestDispatcher(testScheduler)
        val held = FakeAndroidPlatform()
        val holder = CameraKStateHolder(
            CameraConfiguration(),
            CameraEngineFactory { config, environment -> held.engineIn(environment, config) },
            environment = CameraKEnvironment(dispatchers = CameraKDispatchers(main = main, default = main, io = main)),
        )
        holder.start()
        held.settle(testScheduler)
        assertIs<CameraKState.Ready>(holder.cameraState.value, "setup: the holder's camera")

        val running = List(3) { async { holder.capture() } }
        held.settle(testScheduler)
        val fourth = holder.capture()

        val busy = assertIs<CameraKError.Busy>(assertIs<CameraKResult.Failure>(fourth).error)
        assertEquals(CameraOperation.CAPTURE, busy.operation)
        assertEquals(3, held.backend.captures.size, "the fourth capture reached CameraX")
        held.backend.captures.forEach { it.deliverSaved(byteArrayOf(1)) }
        held.settle(testScheduler)
        running.forEach { assertIs<CameraKResult.Success<*>>(it.await(), "a holder capture") }
        holder.closeAndJoin()
    }
}

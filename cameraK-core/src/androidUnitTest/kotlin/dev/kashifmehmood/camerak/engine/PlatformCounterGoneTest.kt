@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.engine

import dev.kashifmehmood.camerak.coroutines.CameraKDispatchers
import dev.kashifmehmood.camerak.error.CameraKError
import dev.kashifmehmood.camerak.error.CameraOperation
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.state.CameraConfiguration
import dev.kashifmehmood.camerak.state.CameraKState
import dev.kashifmehmood.camerak.state.CameraKStateHolder
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
        // Each capture finishes on the engine's image-processing thread, a real one: awaiting it at once
        // would let runTest skip virtual time to the holder's capture deadline first.
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!running.all { it.isCompleted } && System.nanoTime() < deadline) {
            held.settle(testScheduler)
            Thread.sleep(5)
        }
        assertTrue(running.all { it.isCompleted }, "the captures did not finish within 5 s")
        running.forEach { assertIs<CameraKResult.Success<*>>(it.await(), "a holder capture") }
        holder.closeAndJoin()
    }
}

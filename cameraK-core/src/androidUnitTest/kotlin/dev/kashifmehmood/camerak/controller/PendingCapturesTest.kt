@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.engine.FakeAndroidPlatform
import dev.kashifmehmood.camerak.engine.open
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * The engine's captures: the limit of three in flight is the holder's alone since A3'
 * (PlatformCounterGoneTest), and a capture whose caller has already left never reaches CameraX.
 */
@RunWith(RobolectricTestRunner::class)
class PendingCapturesTest {
    private val platform = FakeAndroidPlatform()

    @Test
    fun captureWhoseCallerHasAlreadyLeft_neverReachesCameraXAndKeepsNoSlot() = runTest {
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        val backend = platform.backend

        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            cancel()
            engine.capture()
        }
        platform.settle(testScheduler)

        assertEquals(0, backend.captures.size, "a capture whose caller had already left still reached CameraX")
        repeat(MAX_IN_FLIGHT) { backgroundScope.launch { engine.capture() } }
        platform.settle(testScheduler)
        assertEquals(MAX_IN_FLIGHT, backend.captures.size, "the abandoned capture kept a slot")
        engine.close()
    }

    private companion object {
        const val MAX_IN_FLIGHT = 3
    }
}

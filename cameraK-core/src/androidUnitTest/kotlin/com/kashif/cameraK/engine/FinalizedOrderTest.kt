@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Every recording yields one Finalized, in start order (§5), also when two playable probes overlap
 * on io and the later one finishes first (w7 A3' carry-over 1).
 */
@RunWith(RobolectricTestRunner::class)
class FinalizedOrderTest {
    private val platform = FakeAndroidPlatform()

    @Test
    fun overlappingProbesStillFinalizeInStartOrder() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        val io = HeldDispatcher(main)
        val engine = platform.engineIn(CameraKEnvironment(CameraKDispatchers(main = main, default = main, io = io)))
        val events = recordingEventsOf(engine)
        open(platform, engine)

        // A's stop times out: CameraX has not finalized it within the stop's wait.
        val (first, firstPath) = startRecording(platform, engine)
        val firstStop = async { engine.stopRecording() }
        platform.settle(testScheduler)
        testScheduler.advanceTimeBy(STOP_TIMEOUT_MS + 1)
        platform.settle(testScheduler)
        assertIs<CameraKResult.Failure>(firstStop.await(), "setup: the first stop timed out")

        // A's Finalize lands now, and its probe is still reading when B starts and ends.
        io.holding = true
        first.finalize()
        platform.settle(testScheduler)
        io.holding = false
        assertEquals(1, io.held.size, "setup: the first probe is reading")

        val (second, secondPath) = startRecording(platform, engine)
        val secondStop = async { engine.stopRecording() }
        platform.settle(testScheduler)
        io.holding = true
        second.finalize()
        platform.settle(testScheduler)
        io.holding = false
        assertEquals(2, io.held.size, "setup: both probes are reading")

        // The second probe finishes first.
        io.release(1)
        platform.settle(testScheduler)
        io.release(0)
        platform.settle(testScheduler)

        assertEquals(
            secondPath,
            assertIs<CameraKResult.Success<RecordedVideo>>(secondStop.await(), "the second stop").value.path,
        )
        val finalized = events.filterIsInstance<EngineRecordingEvent.Finalized>().map {
            assertIs<CameraKResult.Success<RecordedVideo>>(it.result, "a Finalized result").value.path
        }
        assertEquals(listOf(firstPath, secondPath), finalized, "Finalized out of start order")
        engine.closeAndJoin()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Passes work to [delegate], except while [holding], when it keeps it until [release]. */
private class HeldDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
    var holding = false
    val held = mutableListOf<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (holding) held += block else delegate.dispatch(context, block)
    }

    /** Lets the [index]th held block run, counting from the oldest still held. */
    fun release(index: Int) = delegate.dispatch(EmptyCoroutineContext, held.removeAt(index))
}

@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.state

import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CameraKScreen's default error slot retries once per Ready it has not retried for, comparing the
 * count it read at its last retry with the count now. A count that fell back would hand a later real
 * Ready a value already used, and that Ready would earn no retry. Two threads race Ready against
 * Error here while a third reads the count: it never reads lower than it read before. Whether the
 * threads overlap is up to the scheduler, so the race runs as several short trials.
 */
class ReadyCountNeverFallsTest {
    @Test
    fun theReadyCountNeverFallsWhileReadyRacesAnotherState() {
        val falls = (1..TRIALS).sumOf { race() }

        assertEquals(0, falls, "reads of the Ready count lower than one read before it, over $TRIALS trials")
    }

    private fun race(): Int {
        val lifecycle = SessionLifecycle(
            engine = FakeCameraEngine(),
            permissionChecker = null,
            scope = CoroutineScope(Job().apply { cancel() }),
            desiredHardware = { error("no session runs") },
            beforeReady = {},
            awaitPreviousClose = {},
            hostStopGrace = {},
            hostStop = {},
        )
        val failure = CameraKState.Error(CameraKError.NotReady)
        val running = CountDownLatch(1)
        val writersDone = AtomicInteger(0)
        val falls = AtomicInteger(0)
        val writers = listOf<(CameraKState) -> CameraKState>({ CameraKState.Ready }, { failure }).map { next ->
            thread {
                running.await()
                repeat(ROUNDS) { lifecycle.transition(next) }
                writersDone.incrementAndGet()
            }
        }
        val reader = thread {
            running.await()
            var seen = 0
            while (writersDone.get() < writers.size) {
                val now = lifecycle.readyCount
                if (now < seen) falls.incrementAndGet()
                seen = maxOf(seen, now)
            }
        }

        running.countDown()
        (writers + reader).forEach { it.join() }
        return falls.get()
    }

    private companion object {
        const val TRIALS = 10
        const val ROUNDS = 2_000_000
    }
}

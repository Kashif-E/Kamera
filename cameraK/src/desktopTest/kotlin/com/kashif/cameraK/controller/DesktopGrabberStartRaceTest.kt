package com.kashif.cameraK.controller

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.awt.image.BufferedImage
import java.lang.management.ManagementFactory
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two start() calls racing from different threads launch one grab loop, so one grabber never has
 * two producers.
 *
 * The scope both starts launch into holds each caller at the moment start() launches its loop,
 * after start() has decided no loop is running. It lets the caller go once the other caller has
 * reached the same point, which shows two loops being launched, or once the other caller is blocked
 * on a lock this caller holds, which shows start() keeps the second caller out. Neither depends on
 * timing.
 */
class DesktopGrabberStartRaceTest {

    @Test
    fun twoConcurrentStarts_launchOneGrabLoop() {
        val fake = FakeFrameGrabber()
        val frames = MutableSharedFlow<BufferedImage>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val parent = SupervisorJob()
        val callers = mutableListOf<Thread>()
        val scope = LaunchGate(parent, callers)
        val sut = CameraGrabber(frames, { throw AssertionError("camera error", it) })
        val bothReady = CyclicBarrier(2)
        try {
            repeat(2) { index ->
                callers += thread(start = false, name = "start-$index") {
                    bothReady.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    sut.start(scope, fake)
                }
            }
            callers.forEach(Thread::start)
            callers.forEach { it.join(TimeUnit.SECONDS.toMillis(GATE_TIMEOUT_SECONDS * 2)) }
            scope.failure?.let { throw it }

            assertEquals(1, parent.children.count(), "grab loops launched by two concurrent start() calls")
        } finally {
            sut.stop()
            parent.cancel()
            fake.openGate()
        }
        assertTrue(fake.awaitReleases(1, GATE_TIMEOUT_SECONDS), "the grab loop never released the grabber")
    }

    /**
     * A scope whose [coroutineContext], which `launch` reads, holds each of [callers] once until the
     * other caller arrives too or is blocked on a monitor the waiting caller owns.
     */
    private class LaunchGate(private val job: Job, private val callers: List<Thread>) : CoroutineScope {
        private val arrived = AtomicInteger(0)
        private val gated = ThreadLocal.withInitial { false }

        @Volatile var failure: Throwable? = null

        override val coroutineContext: CoroutineContext
            get() {
                val caller = Thread.currentThread()
                if (caller in callers && !gated.get()) {
                    gated.set(true)
                    arrived.incrementAndGet()
                    val other = callers.first { it !== caller }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GATE_TIMEOUT_SECONDS)
                    while (arrived.get() < 2 && !isBlockedOn(other, caller)) {
                        if (System.nanoTime() > deadline) {
                            failure = AssertionError("the other start() neither launched nor waited for this one")
                            break
                        }
                        Thread.onSpinWait()
                    }
                }
                return job
            }

        private fun isBlockedOn(waiter: Thread, owner: Thread): Boolean {
            if (waiter.state != Thread.State.BLOCKED) return false
            val info = ManagementFactory.getThreadMXBean().getThreadInfo(waiter.idForThreadInfo()) ?: return false
            return info.lockOwnerId == owner.idForThreadInfo()
        }
    }

    private companion object {
        const val GATE_TIMEOUT_SECONDS = 10L
    }
}

// Thread.threadId() needs Java 19; the build targets 17.
@Suppress("DEPRECATION")
private fun Thread.idForThreadInfo(): Long = id

package com.kashif.cameraK.controller

import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A camera that is not there. Like the Windows `VideoInputFrameGrabber`, it has no internal
 * locking, so it records every access a real native grabber would not survive: two `grab()` calls
 * at once, `stop()`/`release()` while a `grab()` is still inside the handle, and a `grab()` after
 * `release()` with no `start()` in between. Like JavaCV's grabbers, it may be started again after
 * `release()`; `start()` opens a fresh handle.
 *
 * While the gate is closed, `grab()` blocks the way a real grabber blocks until the camera delivers
 * its next frame. [openGate] lets every pending and later `grab()` return at once.
 *
 * With [spinUntilInterrupted], a closed gate is FFmpeg's AVERROR(EAGAIN) instead: `grab()` sleeps 10
 * ms and tries again, and on an interrupt sets the flag again and returns null, as javacv 1.5.12's
 * `FFmpegFrameGrabber.grabFrame` does. Nothing but an interrupt or a frame ends that loop.
 */
internal class FakeFrameGrabber(
    width: Int = 64,
    height: Int = 48,
    private val spinUntilInterrupted: Boolean = false,
    private val nextFrame: (Frame) -> Frame? = { it },
) : FrameGrabber() {
    private val frame = Frame(width, height, Frame.DEPTH_UBYTE, 3)
    private val gate = Semaphore(0)
    private val releases = Semaphore(0)

    @Volatile private var gateOpen = false

    @Volatile private var released = false
    private val inFlight = AtomicInteger(0)

    val violations: MutableList<String> = CopyOnWriteArrayList()
    val grabThreads: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val maxConcurrentGrabs = AtomicInteger(0)
    val grabCount = AtomicInteger(0)
    val startCount = AtomicInteger(0)
    val stopCount = AtomicInteger(0)
    val releaseCount = AtomicInteger(0)

    val firstGrabEntered = CountDownLatch(1)
    val concurrentGrabEntered = CountDownLatch(1)
    val releasedLatch = CountDownLatch(1)

    /** Counted down by the first `grab()` that follows a second `start()`. */
    val grabbedAfterRestart = CountDownLatch(1)

    /** Counted down once a [spinUntilInterrupted] `grab()` has slept for want of a frame. */
    val spinning = CountDownLatch(1)

    fun openGate() {
        gateOpen = true
        gate.release(Int.MAX_VALUE / 2)
    }

    /** Lets the next [count] `grab()` calls return while the gate is closed, one camera frame each. */
    fun allowGrabs(count: Int) = gate.release(count)

    fun awaitReleases(count: Int, seconds: Long): Boolean = releases.tryAcquire(count, seconds, TimeUnit.SECONDS)

    override fun start() {
        if (inFlight.get() > 0) violations += "start() while grab() was in flight"
        startCount.incrementAndGet()
        released = false
    }

    override fun stop() {
        stopCount.incrementAndGet()
        if (inFlight.get() > 0) violations += "stop() while grab() was in flight"
    }

    override fun trigger() = Unit

    override fun grab(): Frame? {
        if (released) violations += "grab() after release()"
        val concurrent = inFlight.incrementAndGet()
        maxConcurrentGrabs.accumulateAndGet(concurrent, ::maxOf)
        if (concurrent > 1) concurrentGrabEntered.countDown()
        grabThreads += Thread.currentThread().name
        firstGrabEntered.countDown()
        if (startCount.get() > 1) grabbedAfterRestart.countDown()
        try {
            if (!gateOpen) {
                if (spinUntilInterrupted) {
                    if (!spinForAFrame()) return null
                } else {
                    gate.tryAcquire(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            }
            grabCount.incrementAndGet()
            return nextFrame(frame)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    // FFmpegFrameGrabber.grabFrame on AVERROR(EAGAIN): sleep and retry; an interrupt is re-set and ends it.
    private fun spinForAFrame(): Boolean {
        while (!gateOpen && !gate.tryAcquire()) {
            spinning.countDown()
            try {
                Thread.sleep(EAGAIN_RETRY_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return true
    }

    override fun release() {
        releaseCount.incrementAndGet()
        if (inFlight.get() > 0) violations += "release() while grab() was in flight"
        released = true
        releasedLatch.countDown()
        releases.release()
    }

    private companion object {
        // Upper bound so a broken test cannot hang the suite; tests open the gate long before this.
        const val GATE_TIMEOUT_SECONDS = 10L
        const val EAGAIN_RETRY_MS = 10L
    }
}

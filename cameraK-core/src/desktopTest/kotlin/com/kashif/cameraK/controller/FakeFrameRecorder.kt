package com.kashif.cameraK.controller

import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameRecorder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * An encoder with no internal locking, like FFmpeg's. It records `stop()`/`release()` while a
 * `record()` is still encoding, and any `record()` after `stop()`.
 *
 * The first `record()` blocks until [finishFirstRecord], so a test can hold the encoder busy while
 * it stops the recording. With a [startFailure], `start()` throws it, as FFmpeg does when it cannot
 * open the output or the codec.
 */
internal class FakeFrameRecorder(private val startFailure: Exception? = null) : FrameRecorder() {
    init {
        // The rate createFrameRecorder gives the real encoder, which turns each timestamp into a
        // frame number at this rate.
        frameRate = 30.0
    }

    private val firstRecordGate = CountDownLatch(1)
    private val inFlight = AtomicInteger(0)

    @Volatile private var stopped = false

    val violations: MutableList<String> = CopyOnWriteArrayList()
    val recordCount = AtomicInteger(0)
    val stopCount = AtomicInteger(0)
    val releaseCount = AtomicInteger(0)

    val firstRecordEntered = CountDownLatch(1)
    val fiveFramesRecorded = CountDownLatch(5)
    val stopCalled = CountDownLatch(1)
    val releasedLatch = CountDownLatch(1)

    /** The `timestamp` set on the recorder when each `record()` was called, in microseconds. */
    val timestamps: MutableList<Long> = CopyOnWriteArrayList()

    /** The recorder's frame size when `start()` was called. */
    @Volatile var sizeAtStart: Pair<Int, Int>? = null
    private val records = Semaphore(0)

    val isRecordInFlight: Boolean get() = inFlight.get() > 0

    /** Waits up to [millis] for [count] more `record()` calls. */
    fun awaitRecords(count: Int, millis: Long): Boolean = records.tryAcquire(count, millis, TimeUnit.MILLISECONDS)

    fun finishFirstRecord() = firstRecordGate.countDown()

    override fun start() {
        sizeAtStart = imageWidth to imageHeight
        startFailure?.let { throw it }
    }

    override fun flush() = Unit

    override fun record(frame: Frame) {
        if (stopped) violations += "record() after stop()"
        inFlight.incrementAndGet()
        timestamps += timestamp
        fiveFramesRecorded.countDown()
        try {
            if (recordCount.getAndIncrement() == 0) {
                firstRecordEntered.countDown()
                firstRecordGate.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        } finally {
            inFlight.decrementAndGet()
            records.release()
        }
    }

    override fun stop() {
        stopCount.incrementAndGet()
        if (inFlight.get() > 0) violations += "stop() while record() was in flight"
        stopped = true
        stopCalled.countDown()
    }

    override fun release() {
        releaseCount.incrementAndGet()
        if (inFlight.get() > 0) violations += "release() while record() was in flight"
        releasedLatch.countDown()
    }

    private companion object {
        // Upper bound so a broken test cannot hang the suite; tests release the gate long before this.
        const val GATE_TIMEOUT_SECONDS = 10L
    }
}

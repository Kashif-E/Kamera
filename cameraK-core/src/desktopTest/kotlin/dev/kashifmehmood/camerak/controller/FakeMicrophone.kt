package dev.kashifmehmood.camerak.controller

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A microphone whose chunks the test hands it ([deliver]), at the capture times the test sets. With
 * [failAfter], the read after that many chunks throws, as a line that is unplugged or closed by the OS.
 * With [endAfter], the read after that many chunks returns null, as a line the OS closed; with
 * [closeFailure], close throws it. A read blocks until a chunk arrives or the microphone is closed.
 */
internal class FakeMicrophone(
    override val sampleRate: Int = 48_000,
    override val channels: Int = 1,
    private val failAfter: Int? = null,
    private val endAfter: Int? = null,
    private val closeFailure: Exception? = null,
) : Microphone {
    private val chunks = LinkedBlockingQueue<AudioChunk>()
    private val delivered = AtomicInteger(0)
    private val readsEntered = AtomicInteger(0)

    val closed = CountDownLatch(1)
    val readBlocked = CountDownLatch(1)

    /**
     * Hands the reader [count] samples (all 1) captured at [millis] on the rig's clock, and returns once
     * the reader has taken them and come back for more: the chunk is then in the recording's queue.
     */
    fun deliver(millis: Long, count: Int = sampleRate / 50 * channels) {
        // The reader enters read() once per chunk it returns, plus the read it is blocked in.
        val target = delivered.get() + chunks.size + 1
        chunks += AudioChunk(ShortArray(count) { 1 }, count, millis * 1_000_000L)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while ((delivered.get() < target || readsEntered.get() <= target) && System.nanoTime() < deadline) {
            Thread.sleep(1)
        }
    }

    override fun read(): AudioChunk? {
        readsEntered.incrementAndGet()
        if (failAfter != null &&
            delivered.get() >= failAfter
        ) {
            throw IllegalStateException("The microphone was unplugged")
        }
        // A line the OS closed: Java Sound's read returns nothing rather than throwing.
        if (endAfter != null && delivered.get() >= endAfter) return null
        while (closed.count > 0) {
            val chunk = chunks.poll(5, TimeUnit.MILLISECONDS)
            if (chunk == null) {
                readBlocked.countDown()
                continue
            }
            delivered.incrementAndGet()
            return chunk
        }
        return null
    }

    override fun close() {
        closed.countDown()
        closeFailure?.let { throw it }
    }
}

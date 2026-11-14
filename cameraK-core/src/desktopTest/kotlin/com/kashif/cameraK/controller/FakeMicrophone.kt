package com.kashif.cameraK.controller

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A microphone whose chunks the test hands it ([deliver]), at the capture times the test sets. With
 * [failAfter], the read after that many chunks throws, as a line that is unplugged or closed by the OS.
 * A read blocks until a chunk arrives or the microphone is closed.
 */
internal class FakeMicrophone(
    override val sampleRate: Int = 48_000,
    override val channels: Int = 1,
    private val failAfter: Int? = null,
) : Microphone {
    private val chunks = LinkedBlockingQueue<AudioChunk>()
    private var delivered = 0

    val closed = CountDownLatch(1)
    val readBlocked = CountDownLatch(1)

    /** Queues [count] samples (all 1) captured at [captureNanos]. */
    fun deliver(captureNanos: Long, count: Int = sampleRate / 50 * channels) {
        chunks += AudioChunk(ShortArray(count) { 1 }, count, captureNanos)
    }

    override fun read(): AudioChunk? {
        if (failAfter != null && delivered >= failAfter) throw IllegalStateException("The microphone was unplugged")
        while (closed.count > 0) {
            val chunk = chunks.poll(5, TimeUnit.MILLISECONDS)
            if (chunk == null) {
                readBlocked.countDown()
                continue
            }
            delivered++
            return chunk
        }
        return null
    }

    override fun close() = closed.countDown()
}

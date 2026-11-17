package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import java.util.concurrent.ArrayBlockingQueue

/**
 * Reads [microphone] on a thread of its own and queues its chunks for the recording, which alone
 * writes them to the recorder. A full queue (the encoder far behind) drops the newest chunk: the first
 * drop is logged when it happens, and how much was dropped in all when the microphone closes. A read that throws, or a line that ends without being closed (Java Sound's read returns
 * nothing for a device the OS closed), is kept as [failure] and ends the reading; [close] ends it
 * quietly.
 */
internal class AudioFeed(private val microphone: Microphone, capacityChunks: Int = DEFAULT_CAPACITY) {
    val sampleRate: Int get() = microphone.sampleRate
    val channels: Int get() = microphone.channels

    private val queue = ArrayBlockingQueue<AudioChunk>(capacityChunks)
    private val dropLogged = atomic(false)
    private val droppedChunks = atomic(0L)
    private val droppedSamples = atomic(0L)
    private val closed = atomic(false)
    private val thread = Thread(::readUntilClosed, THREAD_NAME).apply { isDaemon = true }

    /** Why the microphone stopped, once it failed; read before [drain] to see every chunk it gave. */
    @Volatile var failure: Exception? = null
        private set

    fun start() = thread.start()

    private fun readUntilClosed() {
        try {
            while (true) {
                val chunk = microphone.read() ?: break
                if (!queue.offer(chunk)) {
                    droppedChunks.incrementAndGet()
                    droppedSamples.addAndGet(chunk.count.toLong())
                    if (dropLogged.compareAndSet(false, true)) {
                        CameraKLogger.w(TAG, "The recording fell behind the microphone; samples were dropped")
                    }
                }
            }
        } catch (e: Exception) {
            if (closed.value) return
            CameraKLogger.e(TAG, "Reading the microphone failed; the recording ends", e)
            failure = e
            return
        }
        if (closed.value) return
        CameraKLogger.w(TAG, "The microphone stopped delivering samples; the recording ends")
        failure = IllegalStateException("The microphone stopped delivering samples")
    }

    /** The chunks read since the last drain, oldest first. */
    fun drain(): List<AudioChunk> = ArrayList<AudioChunk>().also { queue.drainTo(it) }

    /** Closes the microphone and waits a moment for the reading thread to end; does nothing twice. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            microphone.close()
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Closing the microphone failed; the recording finishes without it", e)
        }
        if (thread.isAlive) thread.join(CLOSE_WAIT_MILLIS)
        val chunks = droppedChunks.value
        if (chunks > 0) {
            val millis = droppedSamples.value * 1_000 / (sampleRate.toLong() * channels)
            CameraKLogger.w(
                TAG,
                "The recording fell behind the microphone: $chunks chunks ($millis ms of audio) were dropped in all",
            )
        }
    }

    private companion object {
        const val TAG = "CameraK"
        const val THREAD_NAME = "CameraK-microphone"

        // Five seconds of 20 ms chunks.
        const val DEFAULT_CAPACITY = 250
        const val CLOSE_WAIT_MILLIS = 1_000L
    }
}

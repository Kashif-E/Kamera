package com.kashif.cameraK.controller

import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import java.util.concurrent.ArrayBlockingQueue

/**
 * Reads [microphone] on a thread of its own and queues its chunks for the recording, which alone
 * writes them to the recorder. A full queue (the encoder far behind) drops the newest chunk, logged
 * once. A read that throws is kept as [failure] and ends the reading; a close ends it quietly.
 */
internal class AudioFeed(private val microphone: Microphone, capacityChunks: Int = DEFAULT_CAPACITY) {
    val sampleRate: Int get() = microphone.sampleRate
    val channels: Int get() = microphone.channels

    private val queue = ArrayBlockingQueue<AudioChunk>(capacityChunks)
    private val dropLogged = atomic(false)
    private val closed = atomic(false)
    private val thread = Thread(::readUntilClosed, THREAD_NAME).apply { isDaemon = true }

    /** Why the microphone stopped, once it failed; read before [drain] to see every chunk it gave. */
    @Volatile var failure: Exception? = null
        private set

    fun start() = thread.start()

    private fun readUntilClosed() {
        try {
            while (true) {
                val chunk = microphone.read() ?: return
                if (!queue.offer(chunk) && dropLogged.compareAndSet(false, true)) {
                    CameraKLogger.w(TAG, "The recording fell behind the microphone; samples were dropped")
                }
            }
        } catch (e: Exception) {
            if (closed.value) return
            CameraKLogger.e(TAG, "Reading the microphone failed; the recording ends", e)
            failure = e
        }
    }

    /** The chunks read since the last drain, oldest first. */
    fun drain(): List<AudioChunk> = ArrayList<AudioChunk>().also { queue.drainTo(it) }

    /** Closes the microphone and waits a moment for the reading thread to end; does nothing twice. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        microphone.close()
        if (thread.isAlive) thread.join(CLOSE_WAIT_MILLIS)
    }

    private companion object {
        const val TAG = "CameraK"
        const val THREAD_NAME = "CameraK-microphone"

        // Five seconds of 20 ms chunks.
        const val DEFAULT_CAPACITY = 250
        const val CLOSE_WAIT_MILLIS = 1_000L
    }
}

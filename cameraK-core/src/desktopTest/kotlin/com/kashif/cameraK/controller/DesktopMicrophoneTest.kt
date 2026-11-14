package com.kashif.cameraK.controller

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop microphone: the first 16-bit format the default capture line supports, read in chunks
 * stamped with the capture time of their first sample, and a close that ends a read in progress.
 */
class DesktopMicrophoneTest {
    /** A capture line whose reads are handed bytes by the test, and end once it is closed. */
    private class ScriptedLine(override val format: AudioFormat) : CaptureLine {
        val reads = LinkedBlockingQueue<ByteArray>()
        val closed = CountDownLatch(1)

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            while (closed.count > 0) {
                val bytes = reads.poll(10, TimeUnit.MILLISECONDS) ?: continue
                bytes.copyInto(buffer, offset, 0, minOf(length, bytes.size))
                return minOf(length, bytes.size)
            }
            return 0
        }

        override fun close() = closed.countDown()
    }

    private fun format(rate: Float, channels: Int) = AudioFormat(rate, 16, channels, true, false)

    @Test
    fun theFirstSupportedFormatIsChosen() {
        val stereo44 = captureFormat { it.sampleRate == 44_100f && it.channels == 2 }

        assertNotNull(stereo44)
        assertEquals(44_100f, stereo44.sampleRate)
        assertEquals(2, stereo44.channels)
        assertEquals(16, stereo44.sampleSizeInBits)
        assertEquals(AudioFormat.Encoding.PCM_SIGNED, stereo44.encoding)
        assertEquals(false, stereo44.isBigEndian)

        val first = assertNotNull(captureFormat { true })
        assertEquals(48_000f, first.sampleRate, "48 kHz mono comes first")
        assertEquals(1, first.channels)

        assertNull(captureFormat { false }, "no microphone")
    }

    @Test
    fun aChunksCaptureTimeIsItsFirstSample() {
        val line = ScriptedLine(format(48_000f, 1))
        val microphone = JavaSoundMicrophone(line) { 1_100_000_000L }
        // 20 ms of 48 kHz mono, the chunk the microphone reads, little-endian: the samples 1, 2, 3 ...
        line.reads += ByteArray(960 * 2) { if (it % 2 == 0) ((it / 2 + 1) and 0xFF).toByte() else 0 }

        val chunk = assertNotNull(microphone.read())

        assertEquals(48_000, microphone.sampleRate)
        assertEquals(1, microphone.channels)
        assertEquals(960, chunk.count)
        assertEquals(1_080_000_000L, chunk.captureNanos, "read at 1.1 s, so its first sample is 20 ms earlier")
        assertEquals(listOf<Short>(1, 2, 3), chunk.samples.take(3))
    }

    @Test
    fun closeUnblocksARead() {
        val line = ScriptedLine(format(44_100f, 2))
        val microphone = JavaSoundMicrophone(line) { 0L }
        val ended = CountDownLatch(1)
        var chunk: AudioChunk? = AudioChunk(ShortArray(0), 0, 0)
        thread {
            chunk = microphone.read()
            ended.countDown()
        }

        microphone.close()

        assertTrue(ended.await(1, TimeUnit.SECONDS), "a read went on after close")
        assertNull(chunk)
    }
}

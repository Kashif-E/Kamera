package com.kashif.cameraK.controller

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/** [count] interleaved 16-bit samples, the first captured at [captureNanos] on the engine's clock. */
internal class AudioChunk(val samples: ShortArray, val count: Int, val captureNanos: Long)

/** One open microphone line. */
internal interface Microphone {
    val sampleRate: Int
    val channels: Int

    /** Blocks for the next chunk; null once closed; throws when the line fails. */
    fun read(): AudioChunk?

    /** Closes the line, ending a read in progress. */
    fun close()
}

/** Opens the default microphone: null when there is none; throws when one exists but does not open. */
internal fun interface MicrophoneOpener {
    fun open(nanoClock: () -> Long): Microphone?
}

/** What [JavaSoundMicrophone] reads: a started capture line. */
internal interface CaptureLine {
    val format: AudioFormat

    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    fun close()
}

// 16-bit signed little-endian, mono before stereo and 48 kHz before 44.1 kHz: what the recording's AAC takes as is.
private val CAPTURE_FORMATS = listOf(1, 2).flatMap { channels ->
    listOf(48_000f, 44_100f).map { rate -> AudioFormat(rate, 16, channels, true, false) }
}

/** The first capture format the line supports; null when it supports none. */
internal fun captureFormat(isSupported: (AudioFormat) -> Boolean): AudioFormat? =
    CAPTURE_FORMATS.firstOrNull(isSupported)

/**
 * The default microphone through Java Sound, the JDK's own capture API on macOS, Linux and Windows.
 * It reads 20 ms chunks, each stamped with the [nanoClock] time of its first sample.
 */
internal class JavaSoundMicrophone(private val line: CaptureLine, private val nanoClock: () -> Long) : Microphone {
    override val sampleRate: Int = line.format.sampleRate.toInt()
    override val channels: Int = line.format.channels

    private val chunkSamples = sampleRate / CHUNKS_PER_SECOND * channels
    private val bytes = ByteArray(chunkSamples * 2)

    @Volatile private var closed = false

    override fun read(): AudioChunk? {
        var filled = 0
        while (filled < bytes.size && !closed) {
            val read = line.read(bytes, filled, bytes.size - filled)
            if (read <= 0) break
            filled += read
        }
        val count = filled / 2 / channels * channels
        if (count == 0) return null
        val samples = ShortArray(count) { i ->
            ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
        }
        val durationNanos = count / channels * 1_000_000_000L / sampleRate
        return AudioChunk(samples, count, nanoClock() - durationNanos)
    }

    override fun close() {
        closed = true
        line.close()
    }

    private companion object {
        const val CHUNKS_PER_SECOND = 50
    }
}

/** A started Java Sound line. */
private class TargetDataLineCapture(private val line: TargetDataLine) : CaptureLine {
    override val format: AudioFormat get() = line.format

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = line.read(buffer, offset, length)

    override fun close() {
        line.stop()
        line.close()
    }
}

/**
 * The default capture line in the first format it supports, started. No line supports one when the
 * machine has no microphone; opening one that exists throws when it is busy or the OS refuses it.
 */
internal val JavaSoundMicrophoneOpener = MicrophoneOpener { nanoClock ->
    val format = captureFormat { AudioSystem.isLineSupported(DataLine.Info(TargetDataLine::class.java, it)) }
        ?: return@MicrophoneOpener null
    val line = AudioSystem.getTargetDataLine(format)
    try {
        line.open(format)
        line.start()
    } catch (e: Exception) {
        line.close()
        throw e
    }
    JavaSoundMicrophone(TargetDataLineCapture(line), nanoClock)
}

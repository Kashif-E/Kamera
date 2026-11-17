package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.permissions.PermissionStatus
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.abs

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

/** The microphone's prompt was answered with a refusal: [status] is what macOS reports now. */
internal class MicrophoneRefused(val status: PermissionStatus) : Exception("The microphone is $status")

/** [open], unless [refusal] gives the status that refuses the microphone: it then throws [MicrophoneRefused]. */
internal fun accessCheckedOpener(refusal: () -> PermissionStatus?, open: MicrophoneOpener): MicrophoneOpener =
    MicrophoneOpener { nanoClock ->
        refusal()?.let { throw MicrophoneRefused(it) }
        open.open(nanoClock)
    }

/** What [JavaSoundMicrophone] reads: a started capture line. */
internal interface CaptureLine {
    val format: AudioFormat

    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    /** Bytes captured and not read yet. */
    fun available(): Int = 0

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
 * It reads 20 ms chunks, each stamped with the [nanoClock] time of its first sample. Java Sound hands
 * samples over in bursts (a read waits about a quarter of the line's buffer, then the backlog comes
 * at once), so the time a read returns says little: a chunk's time is worked out from the clock less
 * what it and the backlog behind it hold, and a chunk that follows on from the last within
 * [REANCHOR_NANOS] is stamped exactly where the last one ended, so the stamps advance by the samples.
 */
internal class JavaSoundMicrophone(private val line: CaptureLine, private val nanoClock: () -> Long) : Microphone {
    override val sampleRate: Int = line.format.sampleRate.toInt()
    override val channels: Int = line.format.channels

    private val chunkSamples = sampleRate / CHUNKS_PER_SECOND * channels
    private val bytes = ByteArray(chunkSamples * 2)

    @Volatile private var closed = false

    // Where the next chunk starts if no sample was lost; read only on the reading thread.
    private var nextNanos: Long? = null

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
        val backlogFrames = line.available() / 2 / channels
        val estimate = nanoClock() - nanosOf(count / channels + backlogFrames)
        val stamp = nextNanos?.takeIf { abs(estimate - it) <= REANCHOR_NANOS } ?: estimate
        nextNanos = stamp + nanosOf(count / channels)
        return AudioChunk(samples, count, stamp)
    }

    override fun close() {
        closed = true
        line.close()
    }

    private fun nanosOf(frames: Int): Long = frames * 1_000_000_000L / sampleRate

    private companion object {
        const val CHUNKS_PER_SECOND = 50

        // Further than this from where the samples say, the line lost samples (an overrun): start afresh.
        const val REANCHOR_NANOS = 150_000_000L
    }
}

/** A started Java Sound line. */
private class TargetDataLineCapture(private val line: TargetDataLine) : CaptureLine {
    override val format: AudioFormat get() = line.format

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = line.read(buffer, offset, length)

    override fun available(): Int = line.available()

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

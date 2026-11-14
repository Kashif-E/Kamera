package com.kashif.cameraK.controller

import javax.sound.sampled.AudioFormat

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

/** The first capture format the line supports. */
internal fun captureFormat(isSupported: (AudioFormat) -> Boolean): AudioFormat? = TODO()

internal class JavaSoundMicrophone(private val line: CaptureLine, private val nanoClock: () -> Long) : Microphone {
    override val sampleRate: Int get() = TODO()
    override val channels: Int get() = TODO()

    override fun read(): AudioChunk? = TODO()

    override fun close() = TODO()
}

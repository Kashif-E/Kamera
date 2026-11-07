package com.kashif.cameraK.controller

import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameRecorder
import java.io.File
import java.nio.Buffer
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * An encoder that writes what a test scripts, for the rig's engines. `start()` creates the output
 * file, as FFmpeg opens it; each `record()` appends [bytesPerFrame] bytes to it, so the file grows
 * as a real one does, unless [failRecord] gives an exception for that frame's index. With
 * [holdStop], `stop()` waits until [finishStop], the way a test holds a finalization; the rig
 * counts that wait as quiet ([parkedStops]). [onStart] runs first in each `start()`, on the
 * recording's thread, before a scripted [startFailure] is thrown. Each frame's image buffer and a
 * copy of its pixels are kept ([buffers], [pixels]), as the encoder was handed them.
 */
internal class ScriptedFrameRecorder(
    private val path: String,
    private val workers: TrackedWorkers,
    private val bytesPerFrame: Int = 1_000,
    private val failRecord: (Int) -> Exception? = { null },
    private val startFailure: Exception? = null,
    private val onStart: () -> Unit = {},
) : FrameRecorder() {
    init {
        // The rate createFrameRecorder gives the real encoder.
        frameRate = 30.0
    }

    private val lock = ReentrantLock()
    private val finished = lock.newCondition()
    private var stopPermits = 0
    private var stopWaiter: Thread? = null

    @Volatile var holdStop = false

    val recordCount = AtomicInteger(0)
    val stopCount = AtomicInteger(0)
    val releaseCount = AtomicInteger(0)

    /** The `timestamp` set when each frame was written, in microseconds. */
    val timestamps: MutableList<Long> = CopyOnWriteArrayList()

    /** The first image buffer of each frame written, the object the encoder was handed. */
    val buffers: MutableList<Buffer> = CopyOnWriteArrayList()

    /** The pixels of each frame written, copied when it was written. */
    val pixels: MutableList<RecordedPixels> = CopyOnWriteArrayList()

    /** The frame size when `start()` was called. */
    @Volatile var sizeAtStart: Pair<Int, Int>? = null
        private set

    val file: File get() = File(path)

    override fun start() {
        onStart()
        sizeAtStart = imageWidth to imageHeight
        startFailure?.let { throw it }
        file.writeBytes(ByteArray(0))
        workers.bump()
    }

    override fun flush() = Unit

    override fun record(frame: Frame) {
        val index = recordCount.getAndIncrement()
        failRecord(index)?.let {
            workers.bump()
            throw it
        }
        timestamps += timestamp
        buffers += frame.image[0]
        pixels += RecordedPixels.of(frame)
        file.appendBytes(ByteArray(bytesPerFrame))
        workers.bump()
    }

    override fun stop() {
        stopCount.incrementAndGet()
        if (holdStop) {
            lock.withLock {
                stopWaiter = Thread.currentThread()
                workers.bump()
                try {
                    while (stopPermits == 0) finished.await()
                    stopPermits--
                } finally {
                    stopWaiter = null
                }
            }
        }
        workers.bump()
    }

    override fun release() {
        releaseCount.incrementAndGet()
        workers.bump()
    }

    /** Lets a held `stop()` return. */
    fun finishStop() {
        lock.withLock {
            stopPermits++
            finished.signalAll()
        }
        workers.bump()
    }

    /** A `stop()` waiting for [finishStop]: 0 or 1. */
    fun parkedStops(): Int = lock.withLock { if (stopWaiter != null && stopPermits == 0) 1 else 0 }
}

/** A recorded frame's BGR pixels, [width] x [height], rows packed without padding. */
internal class RecordedPixels(val width: Int, val height: Int, private val bgr: ByteArray) {
    /** The pixel at [x], [y] as 0xRRGGBB. */
    fun rgb(x: Int, y: Int): Int {
        val at = (y * width + x) * 3
        return (bgr[at + 2].toInt() and 0xFF shl 16) or (bgr[at + 1].toInt() and 0xFF shl 8) or
            (bgr[at].toInt() and 0xFF)
    }

    companion object {
        /** Copies [frame]'s 3-channel byte image, reading each row at the frame's stride. */
        fun of(frame: Frame): RecordedPixels {
            check(frame.imageDepth == Frame.DEPTH_UBYTE && frame.imageChannels == 3) {
                "a recorded frame of depth ${frame.imageDepth} with ${frame.imageChannels} channels"
            }
            val source = (frame.image[0] as ByteBuffer).duplicate()
            val row = frame.imageWidth * 3
            val bgr = ByteArray(row * frame.imageHeight)
            for (y in 0 until frame.imageHeight) {
                source.position(y * frame.imageStride)
                source.get(bgr, y * row, row)
            }
            return RecordedPixels(frame.imageWidth, frame.imageHeight, bgr)
        }
    }
}

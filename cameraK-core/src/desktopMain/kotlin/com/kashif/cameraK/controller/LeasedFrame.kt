package com.kashif.cameraK.controller

import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FramePlane
import kotlinx.coroutines.channels.Channel
import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * One slot of the producer's frame pool (§8 desktop): an owned `TYPE_3BYTE_BGR` [image], already
 * cropped and mirrored by the blit, and the leases on it. The producer holds one lease from the
 * conversion; each consumer that keeps the frame past the producer's call (an analyzer, the
 * recorder, the preview's hold) [retain]s one and [release]s it once. [FrameConverter] writes the
 * slot again only when no lease is left.
 *
 * Analysis frames read [analysisPlanes]: the slot's pixels in the session's format, converted into
 * a buffer the slot keeps, at most once per frame and only when an analyzer takes the frame.
 */
internal class LeasedFrame internal constructor(
    val width: Int,
    val height: Int,
    private val yuvConversions: AtomicLong = AtomicLong(),
) {
    val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)

    // One Graphics2D for the slot's life, so a frame costs a blit and no allocation.
    internal val graphics: Graphics2D = image.createGraphics().apply { composite = AlphaComposite.Src }

    // TYPE_3BYTE_BGR stores B, G, R per pixel, rows of width * 3 bytes.
    private val bgr: ByteArray = (image.raster.dataBuffer as DataBufferByte).data
    private val leases = AtomicInteger(0)

    /** When the camera delivered the frame in the slot, on the engine's clock. */
    @Volatile var captureNanos: Long = 0L
        private set

    // Written and read on the producer thread only; consumers read the planes it hands them.
    private var generation = 0L
    private var analysisGeneration = -1L
    private var analysisFormat: FrameFormat? = null
    private var analysis = ByteArray(0)
    private var planes: List<FramePlane> = emptyList()

    /** The leases held on the frame in this slot; 0 when the slot is free. */
    val leaseCount: Int get() = leases.get()

    /** Takes the free slot for a new frame, with the producer's lease. Producer thread only. */
    internal fun begin(nanos: Long) {
        check(leases.compareAndSet(0, 1)) { "A leased frame slot was written" }
        captureNanos = nanos
        generation++
    }

    /** One more lease on a frame already leased; returns this frame. */
    fun retain(): LeasedFrame {
        while (true) {
            val held = leases.get()
            check(held > 0) { "retain() on a frame with no lease" }
            if (leases.compareAndSet(held, held + 1)) return this
        }
    }

    /** Gives one lease back; the slot is free when the last one is. */
    fun release() {
        while (true) {
            val held = leases.get()
            check(held > 0) { "release() on a frame with no lease" }
            if (leases.compareAndSet(held, held - 1)) return
        }
    }

    /**
     * This frame's pixels in [format], as planes over the slot's analysis buffer: RGBA_8888 one
     * plane of 4 bytes per pixel; YUV_420 the full-range BT.601 Y, U and V planes of I420, chroma
     * subsampled 2 x 2. Converted on the first call for this frame. Producer thread only, while it
     * holds a lease.
     */
    internal fun analysisPlanes(format: FrameFormat): List<FramePlane> {
        if (analysisGeneration == generation && analysisFormat == format) return planes
        when (format) {
            FrameFormat.RGBA_8888 -> {
                val size = width * height * RGBA_BYTES
                if (analysis.size != size) analysis = ByteArray(size)
                bgrToRgba(bgr, width * height, analysis)
                if (analysisFormat != format) {
                    planes = listOf(DesktopFramePlane(analysis, 0, width * RGBA_BYTES, RGBA_BYTES, size))
                }
            }
            FrameFormat.YUV_420 -> {
                val chromaWidth = (width + 1) / 2
                val chroma = chromaWidth * ((height + 1) / 2)
                val luma = width * height
                if (analysis.size != luma + 2 * chroma) analysis = ByteArray(luma + 2 * chroma)
                bgrToI420(bgr, width, height, analysis)
                yuvConversions.incrementAndGet()
                if (analysisFormat != format) {
                    planes = listOf(
                        DesktopFramePlane(analysis, 0, width, 1, luma),
                        DesktopFramePlane(analysis, luma, chromaWidth, 1, chroma),
                        DesktopFramePlane(analysis, luma + chroma, chromaWidth, 1, chroma),
                    )
                }
            }
        }
        analysisFormat = format
        analysisGeneration = generation
        return planes
    }

    /** Frees the slot's drawing state; its image stays readable for a consumer still holding it. */
    internal fun dispose() = graphics.dispose()

    private companion object {
        const val RGBA_BYTES = 4
    }
}

private fun bgrToRgba(bgr: ByteArray, pixels: Int, out: ByteArray) {
    var i = 0
    var o = 0
    repeat(pixels) {
        out[o] = bgr[i + 2]
        out[o + 1] = bgr[i + 1]
        out[o + 2] = bgr[i]
        out[o + 3] = OPAQUE
        i += 3
        o += 4
    }
}

// Full-range BT.601 in 16-bit fixed point, the weights copyLuma uses for RGBA luma: each row of
// weights sums to 1 << 16 (Y) or to 0 (U, V), so grey stays grey with neutral chroma.
private fun bgrToI420(bgr: ByteArray, width: Int, height: Int, out: ByteArray) {
    val chromaWidth = (width + 1) / 2
    val chromaHeight = (height + 1) / 2
    val uStart = width * height
    val vStart = uStart + chromaWidth * chromaHeight
    var i = 0
    for (p in 0 until width * height) {
        val b = bgr[i].toInt() and 0xFF
        val g = bgr[i + 1].toInt() and 0xFF
        val r = bgr[i + 2].toInt() and 0xFF
        out[p] = ((Y_R * r + Y_G * g + Y_B * b + ROUNDING) shr FIXED_POINT_BITS).toByte()
        i += 3
    }
    for (cy in 0 until chromaHeight) {
        for (cx in 0 until chromaWidth) {
            var r = 0
            var g = 0
            var b = 0
            var n = 0
            for (y in cy * 2 until minOf(cy * 2 + 2, height)) {
                for (x in cx * 2 until minOf(cx * 2 + 2, width)) {
                    val at = (y * width + x) * 3
                    b += bgr[at].toInt() and 0xFF
                    g += bgr[at + 1].toInt() and 0xFF
                    r += bgr[at + 2].toInt() and 0xFF
                    n++
                }
            }
            r /= n
            g /= n
            b /= n
            val u = ((U_R * r + U_G * g + U_B * b + ROUNDING) shr FIXED_POINT_BITS) + CHROMA_ZERO
            val v = ((V_R * r + V_G * g + V_B * b + ROUNDING) shr FIXED_POINT_BITS) + CHROMA_ZERO
            out[uStart + cy * chromaWidth + cx] = u.coerceIn(0, 255).toByte()
            out[vStart + cy * chromaWidth + cx] = v.coerceIn(0, 255).toByte()
        }
    }
}

private const val OPAQUE: Byte = -1
private const val FIXED_POINT_BITS = 16
private const val ROUNDING = 1 shl (FIXED_POINT_BITS - 1)
private const val CHROMA_ZERO = 128
private const val Y_R = 19595
private const val Y_G = 38470
private const val Y_B = 7471
private const val U_R = -11059
private const val U_G = -21709
private const val U_B = 32768
private const val V_R = 32768
private const val V_G = -27439
private const val V_B = -5329

/**
 * One consumer's feed of the producer's frames, conflated: it holds at most the latest frame not
 * yet taken, with a lease of its own, and a newer frame replaces it and gives its lease back. The
 * consumer [next]s a frame, owns its lease and releases it when done. The producer closes the feed
 * when its run ends; the frame still pending is taken first.
 */
internal class FrameFeed {
    private val lock = Any()
    private var pending: LeasedFrame? = null
    private var closed = false
    private val signal = Channel<Unit>(Channel.CONFLATED)

    /** Leases [frame] into the feed; false once the feed is closed, and the frame is not leased. */
    fun offer(frame: LeasedFrame): Boolean {
        synchronized(lock) {
            if (closed) return false
            pending?.release()
            pending = frame.retain()
        }
        signal.trySend(Unit)
        return true
    }

    /** The next frame, with a lease the caller releases; null once the feed is closed and drained. */
    suspend fun next(): LeasedFrame? {
        while (true) {
            synchronized(lock) {
                pending?.let {
                    pending = null
                    return it
                }
                if (closed) return null
            }
            signal.receiveCatching()
        }
    }

    /** No more frames: [next] returns what is pending, then null. */
    fun close() {
        synchronized(lock) { closed = true }
        signal.close()
    }

    /** The consumer is done: the pending frame's lease is given back. */
    fun cancel() {
        synchronized(lock) {
            closed = true
            pending?.release()
            pending = null
        }
        signal.close()
    }
}

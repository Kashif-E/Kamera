package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.utils.CameraKLogger
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicLong

/**
 * Converts grabbed frames into a pool of owned `TYPE_3BYTE_BGR` slots ([LeasedFrame]), blitting
 * each with its crop and mirror ([FrameBlit]) in one draw. Java2DFrameConverter writes every frame
 * into the same image, so its output never leaves this class. A slot is written again only when
 * its lease count is zero (§8 desktop): a frame the producer, an analyzer or the recorder still
 * holds is never overwritten. The pool grows while every slot is leased, up to [capacity] slots
 * ([framePoolSize]); past that, a frame is skipped. When the capacity falls, free slots beyond it
 * are dropped.
 *
 *
 * Not thread-safe: the producer thread that grabs the frames is its only caller. Leases are given
 * back from any thread.
 */
internal class FrameConverter(
    private val yuvConversions: AtomicLong = AtomicLong(),
    private val capacity: () -> Int = { framePoolSize(retainLimits = 0, analyzers = 0) },
) {
    private val converter = Java2DFrameConverter()
    private val slots = ArrayList<LeasedFrame>()
    private var exhaustedLogged = false

    /**
     * The frame, cropped and mirrored by [blit], in a free slot of the pool, with one lease, the
     * caller's; null if it has no picture, cannot be converted, or every slot is leased.
     */
    internal fun convertLeased(frame: Frame, blit: FrameBlit, captureNanos: Long): LeasedFrame? = try {
        converter.convert(frame)?.let { source ->
            val crop = blit.crop(source.width, source.height)
            freeSlot(crop.width, crop.height)?.also { slot ->
                draw(slot, source, crop, blit.mirrored)
                slot.begin(captureNanos)
            }
        }
    } catch (e: Exception) {
        CameraKLogger.e("CameraK", "CameraK: Error converting a camera frame; the frame is skipped", e)
        null
    }

    /** The slots the pool has allocated. */
    internal val slotCount: Int get() = slots.size

    private fun freeSlot(width: Int, height: Int): LeasedFrame? {
        val limit = capacity()
        val slot = pickSlot(width, height, limit)
        trim(limit, keep = slot)
        return slot
    }

    private fun pickSlot(width: Int, height: Int, limit: Int): LeasedFrame? {
        slots.firstOrNull { it.leaseCount == 0 && it.width == width && it.height == height }?.let { return it }
        // A free slot of another size (the camera's mode changed) is replaced.
        val stale = slots.indexOfFirst { it.leaseCount == 0 }
        if (stale >= 0) {
            slots[stale].dispose()
            return LeasedFrame(width, height, yuvConversions).also { slots[stale] = it }
        }
        if (slots.size >= limit) {
            if (!exhaustedLogged) {
                exhaustedLogged = true
                CameraKLogger.w("CameraK", "CameraK: Every frame slot is leased; frames are skipped until one is free")
            }
            return null
        }
        return LeasedFrame(width, height, yuvConversions).also { slots += it }
    }

    // Only the producer leases a free slot, so a slot found free here stays free while it is dropped.
    private fun trim(limit: Int, keep: LeasedFrame?) {
        var excess = slots.size - limit
        if (excess <= 0) return
        val iterator = slots.iterator()
        while (excess > 0 && iterator.hasNext()) {
            val slot = iterator.next()
            if (slot !== keep && slot.leaseCount == 0) {
                slot.dispose()
                iterator.remove()
                excess--
            }
        }
    }

    private fun draw(slot: LeasedFrame, source: BufferedImage, crop: Crop, mirrored: Boolean) {
        val w = slot.width
        val h = slot.height
        val right = crop.x + w
        val bottom = crop.y + h
        if (mirrored) {
            slot.graphics.drawImage(source, w, 0, 0, h, crop.x, crop.y, right, bottom, null)
        } else {
            slot.graphics.drawImage(source, 0, 0, w, h, crop.x, crop.y, right, bottom, null)
        }
    }

    fun release() {
        converter.close()
        slots.forEach { it.dispose() }
        slots.clear()
    }
}

/**
 * The slots the producer's pool may hold (W7b): [retainLimits], the sum of every registration's
 * `maxRetained`, plus one slot for each consumer (the preview, a still, the recorder and each of the
 * [analyzers]) and two of headroom. Each consumer other than the preview keeps at most one frame
 * besides an analyzer's retains, and the preview's two held frames and the one being converted take
 * its slot and the headroom, so the pool never runs dry while an analyzer at its `maxRetained` is
 * skipped.
 */
internal fun framePoolSize(retainLimits: Int, analyzers: Int): Int =
    retainLimits + FIXED_CONSUMERS + analyzers + HEADROOM_SLOTS

// The preview, a still and the recorder.
private const val FIXED_CONSUMERS = 3
private const val HEADROOM_SLOTS = 2

/**
 * The producer's blit of one frame: the centred crop to [aspectRatio] (none when null) and a
 * horizontal mirror. Every consumer reads the blitted frame, so the crop and the mirror reach the
 * preview, stills, recordings and analysis alike (§2.4 desktop).
 */
internal class FrameBlit(val aspectRatio: AspectRatio?, val mirrored: Boolean) {
    /**
     * The centred region of a [width] x [height] frame with [aspectRatio]: the side that is too long
     * is cut, to an even length, so a recording's encoder takes it. The whole frame without a ratio.
     */
    fun crop(width: Int, height: Int): Crop {
        val (ratioWidth, ratioHeight) = when (aspectRatio ?: return Crop(0, 0, width, height)) {
            AspectRatio.RATIO_4_3 -> 4 to 3
            AspectRatio.RATIO_16_9 -> 16 to 9
            AspectRatio.RATIO_9_16 -> 9 to 16
            AspectRatio.RATIO_1_1 -> 1 to 1
        }
        val wide = width.toLong() * ratioHeight
        val tall = height.toLong() * ratioWidth
        return when {
            wide > tall -> {
                val w = even(height * ratioWidth / ratioHeight)
                Crop((width - w) / 2, 0, w, height)
            }
            wide < tall -> {
                val h = even(width * ratioHeight / ratioWidth)
                Crop(0, (height - h) / 2, width, h)
            }
            else -> Crop(0, 0, width, height)
        }
    }

    private fun even(length: Int) = (length and 1.inv()).coerceAtLeast(2)

    companion object {
        val NONE = FrameBlit(null, false)
    }
}

/** A region of a frame in pixels. */
internal class Crop(val x: Int, val y: Int, val width: Int, val height: Int)

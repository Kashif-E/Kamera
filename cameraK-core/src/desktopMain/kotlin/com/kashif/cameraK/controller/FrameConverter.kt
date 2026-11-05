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
 * holds is never overwritten. The pool grows while every slot is leased, up to [MAX_SLOTS]; past
 * that, a frame is skipped.
 *
 * [convert] is the 1.x call: the image it returns is not written again until two further frames
 * have been converted.
 *
 * Not thread-safe: the producer thread that grabs the frames is its only caller. Leases are given
 * back from any thread.
 */
class FrameConverter internal constructor(private val yuvConversions: AtomicLong) {
    constructor() : this(AtomicLong())

    private val converter = Java2DFrameConverter()
    private val slots = ArrayList<LeasedFrame>()
    private val legacyHeld = ArrayDeque<LeasedFrame>()
    private var exhaustedLogged = false

    private var horizontalFlip = false

    fun setHorizontalFlip(flip: Boolean) {
        this.horizontalFlip = flip
    }

    /** Returns the frame as a pool-owned image, or null if it has no picture or cannot be converted. */
    fun convert(frame: Frame): BufferedImage? {
        val leased = convertLeased(frame, FrameBlit(null, horizontalFlip), captureNanos = 0L) ?: return null
        legacyHeld.addLast(leased)
        if (legacyHeld.size > LEGACY_HELD) legacyHeld.removeFirst().release()
        return leased.image
    }

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
        slots.firstOrNull { it.leaseCount == 0 && it.width == width && it.height == height }?.let { return it }
        // A free slot of another size (the camera's mode changed) is replaced.
        val stale = slots.indexOfFirst { it.leaseCount == 0 }
        if (stale >= 0) {
            slots[stale].dispose()
            return LeasedFrame(width, height, yuvConversions).also { slots[stale] = it }
        }
        if (slots.size >= MAX_SLOTS) {
            if (!exhaustedLogged) {
                exhaustedLogged = true
                CameraKLogger.w("CameraK", "CameraK: Every frame slot is leased; frames are skipped until one is free")
            }
            return null
        }
        return LeasedFrame(width, height, yuvConversions).also { slots += it }
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
        legacyHeld.clear()
        slots.forEach { it.dispose() }
        slots.clear()
    }

    private companion object {
        // The frame being collected and the one buffered behind it, for a 1.x caller of convert().
        const val LEGACY_HELD = 2

        // Two for the preview's hold, one being converted, and the frames analyzers and the
        // recorder hold; each analyzer is bounded by its maxRetained.
        const val MAX_SLOTS = 16
    }
}

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

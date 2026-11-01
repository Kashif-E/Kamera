package com.kashif.cameraK.controller
import com.kashif.cameraK.utils.CameraKLogger
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.image.BufferedImage

/**
 * Converts grabbed frames into a small ring of owned `TYPE_3BYTE_BGR` images, mirroring them when
 * asked. Java2DFrameConverter writes every frame into the same image, so its output never leaves
 * this class: each converted frame is drawn into the next ring slot, and the image [convert]
 * returns is not written again until [RING_SIZE] - 1 further frames have been converted.
 *
 * Not thread-safe: the producer thread that grabs the frames is its only caller.
 */
class FrameConverter {
    private val converter = Java2DFrameConverter()
    private val slots = arrayOfNulls<Slot>(RING_SIZE)
    private var nextSlot = 0

    private var horizontalFlip = false

    fun setHorizontalFlip(flip: Boolean) {
        this.horizontalFlip = flip
    }

    /** Returns the frame as a ring-owned image, or null if it has no picture or cannot be converted. */
    fun convert(frame: Frame): BufferedImage? = try {
        converter.convert(frame)?.let { source ->
            val index = nextSlot
            nextSlot = (nextSlot + 1) % RING_SIZE
            val slot = slots[index]?.takeIf { it.fits(source) } ?: Slot(source.width, source.height).also {
                slots[index]?.release()
                slots[index] = it
            }
            slot.draw(source, horizontalFlip)
        }
    } catch (e: Exception) {
        CameraKLogger.e("CameraK", "CameraK: Error converting a camera frame; the frame is skipped", e)
        null
    }

    fun release() {
        converter.close()
        slots.forEachIndexed { index, slot ->
            slot?.release()
            slots[index] = null
        }
    }

    private class Slot(width: Int, height: Int) {
        private val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)

        // One Graphics2D for the slot's life, so a frame costs a blit and no allocation.
        private val graphics: Graphics2D = image.createGraphics().apply { composite = AlphaComposite.Src }

        fun fits(source: BufferedImage) = source.width == image.width && source.height == image.height

        fun draw(source: BufferedImage, mirrored: Boolean): BufferedImage {
            val w = image.width
            val h = image.height
            if (mirrored) {
                graphics.drawImage(source, w, 0, 0, h, 0, 0, w, h, null)
            } else {
                graphics.drawImage(source, 0, 0, null)
            }
            return image
        }

        fun release() = graphics.dispose()
    }

    private companion object {
        // The frame being collected, the one buffered behind it and the one being written.
        const val RING_SIZE = 3
    }
}

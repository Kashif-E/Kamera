package com.kashif.cameraK.controller

import org.bytedeco.javacv.Frame
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * The producer converts each frame into a slot of [FrameConverter]'s pool and holds one lease on
 * it; every consumer that keeps the frame (an analyzer, a recorder, the preview's hold) holds a
 * lease of its own. A slot is written again only once its lease count is zero (§8 desktop, D4').
 */
class FrameConverterTest {
    @Test
    fun slotReusedOnlyAtLeaseZero() {
        val sut = FrameConverter()
        try {
            val held = sut.convertLeased(grey(1), FrameBlit.NONE, captureNanos = 1)!!
            // A second lease, given back: the first still holds the slot.
            held.retain().release()

            repeat(10) { n ->
                val next = sut.convertLeased(grey(n + 2), FrameBlit.NONE, captureNanos = n + 2L)!!
                assertNotSame(held.image, next.image, "frame ${n + 2} was written into a leased slot")
                next.release()
            }

            assertEquals(1, held.image.raster.getSample(0, 0, 0), "the leased frame was overwritten")
            assertEquals(1L, held.captureNanos, "the leased frame's capture time changed")
            assertEquals(2, sut.slotCount, "slots: the leased one, and one reused at lease zero")

            held.release()
            // Both slots are free now: two frames kept at once take exactly those two.
            val first = sut.convertLeased(grey(20), FrameBlit.NONE, captureNanos = 20)!!
            val second = sut.convertLeased(grey(21), FrameBlit.NONE, captureNanos = 21)!!
            assertEquals(2, sut.slotCount, "a slot was allocated though both were free")
            assertTrue(held.image === first.image || held.image === second.image, "the released slot was not reused")
            first.release()
            second.release()
        } finally {
            sut.release()
        }
    }

    // A 4 x 2 frame whose every byte is [value].
    private fun grey(value: Int): Frame = Frame(4, 2, Frame.DEPTH_UBYTE, 3).apply {
        val pixels = image[0] as ByteBuffer
        for (i in 0 until pixels.capacity()) pixels.put(i, value.toByte())
    }
}

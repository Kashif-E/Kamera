package com.kashif.cameraK.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import java.awt.image.BufferedImage
import java.lang.management.ManagementFactory
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Steady-state desktop preview reuses its buffers: a stream of 720p frames is drawn through the same
 * two bitmaps, and turning a frame into a bitmap allocates nothing the size of a frame.
 */
class PreviewFrameRendererTest {

    private val frames = List(2) { BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR) }

    @Test
    fun aStreamOfFrames_isDrawnThroughTheSameTwoBitmaps() {
        val renderer = PreviewFrameRenderer()
        try {
            val bitmaps = Collections.newSetFromMap(IdentityHashMap<ImageBitmap, Boolean>())
            repeat(FRAMES) { bitmaps += renderer.render(frames[it % 2]) }

            assertEquals(2, bitmaps.size, "distinct bitmaps for $FRAMES frames")
        } finally {
            renderer.close()
        }
    }

    @Test
    fun afterWarmUp_aFrameAllocatesNothingTheSizeOfAFrame() {
        val renderer = PreviewFrameRenderer()
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        try {
            repeat(WARM_UP_FRAMES) { renderer.render(frames[it % 2]) }

            val before = threads.currentThreadAllocatedBytes
            repeat(FRAMES) { renderer.render(frames[it % 2]) }
            val perFrame = (threads.currentThreadAllocatedBytes - before) / FRAMES

            // A frame's pixels are WIDTH * HEIGHT * 4 bytes as a bitmap. Any per-frame copy of them is
            // at least that; fixed bookkeeping (JNI wrappers, small objects) is far below 1% of it.
            val frameBytes = WIDTH.toLong() * HEIGHT * 4
            assertTrue(perFrame < frameBytes / 100, "allocated $perFrame bytes per frame; a frame is $frameBytes")
        } finally {
            renderer.close()
        }
    }

    @Test
    fun theDrawnBitmap_hasTheFramesPixels() {
        val renderer = PreviewFrameRenderer()
        try {
            val frame = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR).apply {
                setRGB(0, 0, 0x123456)
                setRGB(WIDTH - 1, HEIGHT - 1, 0xA0B0C0)
            }

            val pixels = renderer.render(frame).toPixelMap()

            assertEquals(Color(0xFF123456), pixels[0, 0])
            assertEquals(Color(0xFFA0B0C0), pixels[WIDTH - 1, HEIGHT - 1])
        } finally {
            renderer.close()
        }
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val WARM_UP_FRAMES = 6
        const val FRAMES = 20
    }
}

@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.frames

import com.kashif.cameraK.controller.backCamera
import com.kashif.cameraK.controller.lumaBytes
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import platform.Foundation.NSLock
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Retained sample buffers are capped globally (design §8): at most one per analyzer and two overall,
 * because AVCaptureVideoDataOutput stalls when its small buffer pool is held. Past the cap, retain()
 * copies the luma plane, so the retained frame still reads the frame's luma.
 */
class SampleBufferPoolTest {
    private val driver = AppleEngineDriver()

    /** Frames retained by analyzers, in the order they were retained. */
    private class Kept {
        private val lock = NSLock()
        private val frames = mutableListOf<RetainedFrame>()

        fun add(frame: RetainedFrame) = locked { frames += frame }

        val all: List<RetainedFrame> get() = locked { frames.toList() }

        private fun <T> locked(block: () -> T): T {
            lock.lock()
            try {
                return block()
            } finally {
                lock.unlock()
            }
        }
    }

    private val RetainedFrame.holdsBuffer: Boolean get() = (this as AppleRetainedFrame).holdsSampleBuffer

    @Test
    fun atMostOneRetainedPerAnalyzer() = driver.runCase {
        val engine = driver.createEngine(CameraConfiguration { frameAnalysis = true })
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        val kept = Kept()
        val registration =
            engine.frames.register(FrameSpec(maxRetained = 3, synchronous = true)) { kept.add(it.retain()) }
        driver.settle()
        val second = { x: Int, y: Int -> (200 - x - y * 8) and 0xFF }

        assertTrue(driver.deliverFrame(), "no frame output took the first sample buffer")
        assertTrue(driver.deliverFrame(luma = second), "no frame output took the second sample buffer")
        assertEquals(2, kept.all.size, "frames the analyzer retained")
        assertEquals(
            listOf(true, false),
            kept.all.map { it.holdsBuffer },
            "whether each retained frame holds its sample buffer: one per analyzer, then a luma copy",
        )
        val copied = ByteArray(8 * 4)
        kept.all[1].copyLuma(copied)
        assertContentEquals(lumaBytes(8, 4, second), copied, "the luma the copy kept")

        kept.all.forEach { it.close() }
        assertTrue(driver.deliverFrame(), "no frame output took the third sample buffer")
        assertTrue(kept.all[2].holdsBuffer, "a retain after the analyzer let its buffer go did not hold one")
        kept.all[2].close()
        registration.close()
        engine.close()
    }

    @Test
    fun thirdRetainOverallCopiesLuma() = driver.runCase {
        val engine = driver.createEngine(CameraConfiguration { frameAnalysis = true })
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        val kept = List(3) { Kept() }
        val registrations = kept.map { mine ->
            engine.frames.register(FrameSpec(synchronous = true)) { mine.add(it.retain()) }
        }
        driver.settle()

        assertTrue(driver.deliverFrame(), "no frame output took the sample buffer")
        val retained = kept.map { it.all.single() }
        assertEquals(
            listOf(true, true, false),
            retained.map { it.holdsBuffer },
            "whether each analyzer's retained frame holds the sample buffer: two overall, then a luma copy",
        )
        val copied = ByteArray(8 * 4)
        retained[2].copyLuma(copied)
        assertContentEquals(lumaBytes(8, 4), copied, "the luma the third analyzer's copy kept")
        retained.forEach { it.close() }
        registrations.forEach { it.close() }
        engine.close()
    }
}

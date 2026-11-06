package com.kashif.cameraK.controller

import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FrameSpec
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A frame whose conversion to analysis planes throws (W7b): the frame is skipped, the analyzer's lease
 * and the frame's retain are given back, and the registration's outstanding count returns to zero,
 * so once it is closed the producer's pool shrinks back. Removing the release from the conversion's
 * catch keeps the slot leased and the pool sized for an analyzer that is gone.
 */
class DesktopAnalysisConversionFailureTest {
    @Test
    fun aConversionThatThrowsReleasesTheLeaseAndTheOutstandingFrame() {
        val source = DesktopFrameSource(
            format = FrameFormat.YUV_420,
            analysisSize = null,
            newWorker = { error("a synchronous registration needs no worker") },
            nanoTime = { 0L },
            convert = { _, _, _ -> throw IllegalStateException("analysisPlanes failed") },
        )
        val idlePool = source.poolSize
        val analyzed = AtomicInteger(0)
        val registration = source.register(FrameSpec(synchronous = true)) { analyzed.incrementAndGet() }
        val frame = LeasedFrame(width = 4, height = 4).apply { begin(nanos = 1L) }

        source.deliver(frame)

        assertEquals(0, analyzed.get(), "frames analyzed that could not be converted")
        assertEquals(1, frame.leaseCount, "leases left on the frame: only the producer's")

        registration.close()
        assertEquals(idlePool, source.poolSize, "the pool once the registration closed with nothing outstanding")
    }
}

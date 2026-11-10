package com.kashif.cameraK.controller

import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FrameRegistration
import com.kashif.cameraK.frames.FrameSpec
import com.kashif.cameraK.frames.RetainedFrame
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A registration closed while the producer hands it a frame, after its gate let the frame in and
 * before the frame was counted as handed out: the analyzer still gets the frame and may retain it,
 * so the pool keeps counting the registration until that frame comes back. The gate reads the clock
 * as it lets a frame in, which is where this test closes the registration.
 */
class DesktopFramePoolCloseRaceTest {
    @Test
    fun aRegistrationClosedAsItsFrameEntersStaysCountedUntilTheFrameComesBack() {
        var registration: FrameRegistration? = null
        var closeOnEntry = false
        val source = DesktopFrameSource(
            format = FrameFormat.YUV_420,
            analysisSize = null,
            newWorker = { error("a synchronous registration needs no worker") },
            nanoTime = {
                if (closeOnEntry) {
                    closeOnEntry = false
                    registration?.close()
                }
                0L
            },
        )
        val idlePool = source.poolSize
        val kept = mutableListOf<RetainedFrame>()
        registration = source.register(FrameSpec(synchronous = true)) { frame -> kept += frame.retain() }
        val countedPool = source.poolSize
        val frame = LeasedFrame(width = 4, height = 4).apply { begin(nanos = 1L) }

        closeOnEntry = true
        source.deliver(frame)

        assertEquals(1, kept.size, "the frame the analyzer kept")
        assertEquals(countedPool, source.poolSize, "the pool while the closed registration's analyzer keeps a frame")
        kept.single().close()
        assertEquals(idlePool, source.poolSize, "the pool once the kept frame came back")
    }
}

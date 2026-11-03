package com.kashif.cameraK.frames

import com.kashif.cameraK.utils.CameraKLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** One analyzer's admission: busy skips, the maxFps throttle, retained frames, and a throwing analyzer. */
class AnalyzerGateTest {
    private var now = 0L
    private val ms = 1_000_000L

    /** A platform source in miniature: it asks the gate first and copies only for a lease. */
    private inner class Source(val gate: AnalyzerGate) {
        val copied = mutableListOf<Int>()
        val released = mutableListOf<Int>()

        fun offer(frame: Int, analyze: (AnalyzerLease) -> Unit = {}): AnalyzerLease? {
            val lease = gate.tryEnter(onReleased = { released += frame }) ?: return null
            copied += frame
            lease.deliver { analyze(lease) }
            return lease
        }

        /** Offers a frame whose analyzer is still running when this returns: the entry is not released yet. */
        fun enter(frame: Int): AnalyzerLease? = gate.tryEnter(onReleased = { released += frame })?.also {
            copied +=
                frame
        }
    }

    @Test
    fun busyAnalyzerSkipsAndNeverQueues() {
        val source = Source(AnalyzerGate(maxFps = null, nanoTime = { now }))

        val running = assertNotNull(source.enter(1))
        assertNull(source.enter(2), "a frame offered while the analyzer runs")
        assertNull(source.enter(3), "a frame offered while the analyzer runs")
        running.deliver { }
        assertEquals(listOf(1), source.copied, "a skipped frame was copied")
        assertEquals(listOf(1), source.released)

        assertNotNull(source.offer(4))
        assertEquals(listOf(1, 4), source.copied, "the skipped frames were queued and delivered later")
    }

    @Test
    fun maxFpsCountsAttemptsBeforeAnyCopy() {
        val source = Source(AnalyzerGate(maxFps = 10f, nanoTime = { now }))

        assertNotNull(source.offer(1))
        now = 50 * ms
        assertNull(source.offer(2), "50 ms after an attempt at 10 fps")
        now = 100 * ms - 1
        assertNull(source.offer(3), "just under 100 ms after an attempt at 10 fps")
        assertEquals(listOf(1), source.copied, "a throttled frame was copied")

        now = 100 * ms
        assertNotNull(source.offer(4) { throw IllegalStateException("analysis failed") })
        now = 150 * ms
        assertNull(source.offer(5), "an attempt whose analyzer threw still counts")

        now = 200 * ms
        val held = assertNotNull(source.enter(6))
        now = 300 * ms
        assertNull(source.enter(7), "busy")
        held.deliver { }
        now = 310 * ms
        assertNotNull(source.offer(8), "a frame skipped because the analyzer was busy is not an attempt")
        assertEquals(listOf(1, 4, 6, 8), source.copied)
    }

    @Test
    fun registrationAtMaxRetainedIsSkippedOthersStillReceive() {
        val holding = Source(AnalyzerGate(maxFps = null, nanoTime = { now }))
        val other = Source(AnalyzerGate(maxFps = null, nanoTime = { now }))
        val roomForTwo = Source(AnalyzerGate(maxFps = null, nanoTime = { now }, maxRetained = 2))

        val kept = assertNotNull(holding.offer(1) { it.retain() })
        assertNotNull(roomForTwo.offer(1) { it.retain() })
        assertEquals(emptyList(), holding.released, "a retained frame was released when its callback returned")

        assertNull(holding.offer(2), "a registration holding maxRetained frames")
        assertNotNull(other.offer(2), "another registration while the first holds its frame")
        assertNotNull(roomForTwo.offer(2) { it.retain() }, "one retained frame of two allowed")
        assertNull(roomForTwo.offer(3), "two retained frames of two allowed")

        kept.release()
        assertEquals(listOf(1), holding.released)
        assertNotNull(holding.offer(4), "after the retained frame was released")
    }

    @Test
    fun frameReleasedExactlyOnceWhenAnalyzerThrows() {
        val source = Source(AnalyzerGate(maxFps = null, nanoTime = { now }))

        val lease = assertNotNull(source.offer(1) { throw IllegalStateException("analysis failed") })
        assertEquals(listOf(1), source.released, "the frame after a throwing analyzer")
        lease.release()
        lease.release()
        assertEquals(listOf(1), source.released, "released more than once")
        assertNotNull(source.offer(2), "the analyzer is idle again")

        val kept = assertNotNull(
            source.offer(3) {
                it.retain()
                throw IllegalStateException("analysis failed after retaining")
            },
        )
        assertEquals(listOf(1, 2), source.released, "a frame retained before the throw is still held")
        kept.release()
        kept.release()
        assertEquals(listOf(1, 2, 3), source.released)
        assertFailsWith<IllegalStateException>("retain after release") { kept.retain() }
    }

    @Test
    fun throwingAnalyzerIsCountedAndLogged() {
        val failure = IllegalStateException("analysis failed")
        val logged = mutableListOf<Pair<CameraKLogger.Level, Throwable?>>()
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, _, throwable -> logged += level to throwable }
        try {
            val gate = AnalyzerGate(maxFps = null, nanoTime = { now })
            val source = Source(gate)

            source.offer(1) { throw failure }
            source.offer(2) { }
            source.offer(3) { throw failure }

            assertEquals(2, gate.failures)
            assertEquals(2, logged.size, "logged: $logged")
            assertTrue(logged.all { it.first == CameraKLogger.Level.WARN }, "logged: $logged")
            assertSame(failure, logged.first().second)
        } finally {
            CameraKLogger.sink = null
            CameraKLogger.enabled = false
        }
    }
}

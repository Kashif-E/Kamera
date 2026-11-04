package com.kashif.cameraK.frames

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * The gate applies the same fatal rule as the plugin host: on the JVM a VirtualMachineError leaves
 * the process unable to run correctly, so deliver rethrows it instead of logging it. JVM only,
 * because Kotlin/Native has no VirtualMachineError; the non-fatal case is in AnalyzerGateTest.
 */
class AnalyzerGateFatalTest {
    private class SimulatedOutOfMemory : VirtualMachineError("simulated: heap exhausted")

    @Test
    fun aFatalThrowablePropagatesFromDeliver() {
        val gate = AnalyzerGate(maxFps = null, nanoTime = { 0L })
        val lease = assertNotNull(gate.tryEnter())
        val fatal = SimulatedOutOfMemory()

        val thrown = assertFailsWith<SimulatedOutOfMemory>("deliver absorbed a fatal throwable") {
            lease.deliver { throw fatal }
        }

        assertSame(fatal, thrown)
        assertEquals(0, gate.failures, "a fatal throwable was counted as an analyzer failure")
    }

    // A source that survives the fatal throwable (a supervisor above it, or a test) must not find the
    // analyzer stuck busy and the frame's slot never returned.
    @Test
    fun aFatalThrowableStillReleasesTheEntry() {
        val gate = AnalyzerGate(maxFps = null, nanoTime = { 0L })
        var released = 0
        val lease = assertNotNull(gate.tryEnter(onReleased = { released++ }))

        assertFailsWith<SimulatedOutOfMemory> { lease.deliver { throw SimulatedOutOfMemory() } }

        assertEquals(1, released, "onReleased did not run after a fatal throwable propagated")
        assertNotNull(gate.tryEnter(), "the gate refused the next frame after a fatal throwable propagated")
    }
}

package com.kashif.cameraK.testing

import com.kashif.cameraK.frames.FrameSpec
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * The fake applies the gate's fatal rule: on the JVM a VirtualMachineError propagates from emit
 * instead of being logged. JVM only, because Kotlin/Native has no VirtualMachineError; the
 * non-fatal cases are in FakeFrameSourceTest.
 */
class FakeFrameSourceFatalTest {
    private class SimulatedOutOfMemory : VirtualMachineError("simulated: heap exhausted")

    @Test
    fun aFatalThrowablePropagatesFromEmit() {
        val source = FakeFrameSource()
        val fatal = SimulatedOutOfMemory()
        source.register(FrameSpec()) { throw fatal }

        val thrown = assertFailsWith<SimulatedOutOfMemory>("emit absorbed a fatal throwable") {
            source.emit(TestFrame.luma(2, 2, ByteArray(4)))
        }

        assertSame(fatal, thrown)
    }
}

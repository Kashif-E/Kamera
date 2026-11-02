package com.kashif.cameraK.coroutines

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * On Android, as on the JVM, a VirtualMachineError (OutOfMemoryError among them) leaves the process
 * unable to run correctly, so the broad catch rethrows it. A LinkageError means a native library is
 * missing, which CameraK reports as an error, so it is caught.
 */
class SuspendRunCatchingFatalTest {
    private class SimulatedOutOfMemory : VirtualMachineError("simulated: heap exhausted")

    @Test
    fun virtualMachineErrorIsRethrown() = runTest {
        val fatal = SimulatedOutOfMemory()
        assertSame(fatal, assertFailsWith<SimulatedOutOfMemory> { suspendRunCatching<Unit> { throw fatal } })
    }

    @Test
    fun outOfMemoryErrorIsRethrown() = runTest {
        val fatal = OutOfMemoryError("simulated")
        assertSame(fatal, assertFailsWith<OutOfMemoryError> { suspendRunCatching<Unit> { throw fatal } })
    }

    @Test
    fun linkageErrorIsCaught() = runTest {
        val missing = UnsatisfiedLinkError("no jniavformat in java.library.path")
        assertSame(missing, suspendRunCatching<Unit> { throw missing }.exceptionOrNull())
    }
}

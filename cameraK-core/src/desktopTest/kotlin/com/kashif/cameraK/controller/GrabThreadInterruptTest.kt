package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.newDedicatedWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The grab thread's interrupt flag is clear when the grab loop stops and releases its grabber
 * (§2.4 desktop). `runInterruptible` clears only the interrupt it sent itself; a grabber that
 * re-sets the flag on its own thread, as javacv 1.5.12's `grabFrame` does when its EAGAIN sleep
 * is interrupted ("reset interrupt to be nice"), leaves it set, and a native release that sleeps
 * or waits would then fail at once.
 */
class GrabThreadInterruptTest {
    @Test
    fun interruptFlagIsClearBeforeRelease() = runBlocking {
        val camera = SelfInterruptingGrabber()
        val failures = CopyOnWriteArrayList<Throwable>()
        val listener = object : GrabLoopListener {
            override fun onFrame(frame: LeasedFrame) = Unit

            override fun onFailure(failure: Throwable, opened: Boolean) {
                failures += failure
            }
        }
        val scope = CoroutineScope(SupervisorJob())
        try {
            val run = CameraGrabber(MutableSharedFlow(), { })
                .startRun(scope, CustomGrabberSource(camera), desktopLensInfo(), ::newDedicatedWorker, listener)

            withTimeout(TIMEOUT_MS) { run.join() }

            assertEquals(1, failures.size, "the run ended without reporting the camera's null frame")
            assertEquals(
                listOf(false),
                camera.interruptedAtStop,
                "the interrupt flag when the loop stopped the grabber",
            )
            assertEquals(listOf(false), camera.interruptedAtRelease, "the interrupt flag when the loop released it")
        } finally {
            scope.cancel()
        }
    }

    /** Opens, then answers its first grab() as an interrupted EAGAIN sleep does: the flag set again, no frame. */
    private class SelfInterruptingGrabber : FrameGrabber() {
        val interruptedAtStop = CopyOnWriteArrayList<Boolean>()
        val interruptedAtRelease = CopyOnWriteArrayList<Boolean>()

        override fun start() = Unit

        override fun stop() {
            interruptedAtStop += Thread.currentThread().isInterrupted
        }

        override fun trigger() = Unit

        override fun grab(): Frame? {
            Thread.currentThread().interrupt()
            return null
        }

        override fun release() {
            interruptedAtRelease += Thread.currentThread().isInterrupted
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}

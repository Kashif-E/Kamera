package com.kashif.cameraK.controller

import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.result.ImageCaptureResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The controller owns its session: startSession() has started the grab loop by the time it returns,
 * so a stop or cleanup that follows it always reaches the camera, a capture right after it gets a
 * frame, and a controller that has been cleaned up never opens the camera again.
 */
class DesktopSessionOwnershipTest {

    private val home: File = Files.createTempDirectory("camerak-session").toFile()

    // Holds whatever is dispatched to it until the test runs it, as a busy Dispatchers.Default would.
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue += block
        }

        fun runQueued() {
            while (true) queue.poll()?.run() ?: return
        }
    }

    private fun controller(grabber: FakeFrameGrabber, dispatcher: CoroutineDispatcher) = CameraController(
        imageFormat = ImageFormat.JPEG,
        directory = Directory.PICTURES,
        customGrabber = grabber,
        sessionDispatcher = dispatcher,
        recorderFactory = { _, _ -> FakeFrameRecorder().apply { finishFirstRecord() } },
        userHome = home,
    )

    @Test
    fun stopSession_rightAfterStartSession_leavesTheCameraClosed() =
        assertCameraClosedAfter { sut -> sut.stopSession() }

    @Test
    fun cleanup_rightAfterStartSession_leavesTheCameraClosed() = assertCameraClosedAfter { sut -> sut.cleanup() }

    private fun assertCameraClosedAfter(end: (CameraController) -> Unit) {
        val fake = FakeFrameGrabber().apply { openGate() }
        val dispatcher = QueuedDispatcher()
        val sut = controller(fake, dispatcher)
        try {
            sut.startSession()
            end(sut)
            // Whatever the session start left for later runs now, after the session was ended.
            dispatcher.runQueued()
            fake.firstGrabEntered.await(2, TimeUnit.SECONDS)

            assertTrue(
                awaitCondition(5_000) { fake.startCount.get() == fake.releaseCount.get() },
                "the camera is still open after the session ended: started ${fake.startCount.get()} " +
                    "times, released ${fake.releaseCount.get()} times",
            )
        } finally {
            sut.cleanup()
            home.deleteRecursively()
        }
    }

    @Test
    fun startSession_afterCleanup_neverOpensTheCamera() {
        val fake = FakeFrameGrabber().apply { openGate() }
        val dispatcher = QueuedDispatcher()
        val sut = controller(fake, dispatcher)
        try {
            sut.startSession()
            dispatcher.runQueued()
            assertTrue(fake.firstGrabEntered.await(5, TimeUnit.SECONDS), "the first session never grabbed")
            sut.cleanup()
            assertTrue(fake.awaitReleases(1, 5), "cleanup() did not release the camera")

            sut.startSession()
            dispatcher.runQueued()

            assertTrue(
                !awaitCondition(1_000) { fake.startCount.get() > 1 },
                "a cleaned-up controller opened the camera again",
            )
            assertEquals(fake.startCount.get(), fake.releaseCount.get(), "camera starts and releases")
        } finally {
            sut.cleanup()
            home.deleteRecursively()
        }
    }

    @Test
    fun aCapture_rightAfterStartSession_getsTheCamerasFirstFrame() = runBlocking<Unit> {
        val fake = FakeFrameGrabber().apply { openGate() }
        // Never run: a capture must not depend on when the session-start work is scheduled.
        val sut = controller(fake, QueuedDispatcher())
        try {
            sut.startSession()
            val result = withTimeout(10_000) { sut.takePictureToFile() }

            assertIs<ImageCaptureResult.SuccessWithFile>(result, "capture right after startSession(): $result")
        } finally {
            sut.cleanup()
            home.deleteRecursively()
        }
    }

    // Waits for a condition the grab loop's own thread makes true, checking it every few milliseconds.
    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MS)
        }
        return condition()
    }

    private companion object {
        const val POLL_MS = 10L
    }
}

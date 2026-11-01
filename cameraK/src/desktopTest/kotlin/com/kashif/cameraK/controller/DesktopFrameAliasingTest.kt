package com.kashif.cameraK.controller

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every frame the producer publishes on `frameFlow` is its own image, and the producer does not
 * write it again while the next two frames are published, so a collector that is one frame behind
 * never reads a frame that is being overwritten.
 */
class DesktopFrameAliasingTest {

    @Test
    fun eachPublishedFrame_isItsOwnImage_andStaysUnchangedWhileTheNextTwoArePublished() =
        assertPublishedFramesStayIntact(horizontalFlip = false)

    @Test
    fun withHorizontalFlip_eachPublishedFrame_isItsOwnImage_andStaysUnchangedWhileTheNextTwoArePublished() =
        assertPublishedFramesStayIntact(horizontalFlip = true)

    private fun assertPublishedFramesStayIntact(horizontalFlip: Boolean) {
        val fake = numberedFrames().apply { openGate() }
        val frames = MutableSharedFlow<BufferedImage>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val scope = CoroutineScope(SupervisorJob())
        val received = CopyOnWriteArrayList<Pair<BufferedImage, Int>>()
        val problems = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val enough = CountDownLatch(FRAMES)
        // Unconfined runs the collector inside the producer's tryEmit, so each check below runs
        // after frame n is published and before frame n + 1 is converted.
        scope.launch(Dispatchers.Unconfined) {
            frames.collect { image ->
                val number = received.size
                received.takeLast(2).forEachIndexed { i, (earlier, valueWhenPublished) ->
                    val earlierNumber = number - received.takeLast(2).size + i
                    when {
                        earlier === image -> problems += "frame $number reuses the image of frame $earlierNumber"
                        earlier.firstSample() != valueWhenPublished ->
                            problems += "frame $earlierNumber changed when frame $number was published"
                    }
                }
                received += image to image.firstSample()
                enough.countDown()
            }
        }
        val sut = CameraGrabber(frames, { errors += it }).apply { setHorizontalFlip(horizontalFlip) }
        try {
            sut.start(scope, fake)

            assertTrue(enough.await(5, TimeUnit.SECONDS), "only ${received.size} frames were published")
            sut.stop()
            assertTrue(fake.releasedLatch.await(5, TimeUnit.SECONDS), "the grabber was never released")
            assertEquals(emptyList(), problems.toList())
            assertEquals(emptyList(), errors.toList())
        } finally {
            sut.stop()
            scope.cancel()
        }
    }

    /** A camera whose every frame is a different solid grey, so each frame's pixels identify it. */
    private fun numberedFrames(): FakeFrameGrabber {
        val count = AtomicInteger(0)
        return FakeFrameGrabber { frame ->
            val value = (count.incrementAndGet() % 200 + 20).toByte()
            val pixels = frame.image[0] as ByteBuffer
            for (i in 0 until pixels.capacity()) pixels.put(i, value)
            frame
        }
    }

    private fun BufferedImage.firstSample(): Int = raster.getSample(0, 0, 0)

    private companion object {
        const val FRAMES = 8
    }
}

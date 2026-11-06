@file:OptIn(CameraKPlatformApi::class)

package com.kashif.cameraK.controller

import android.graphics.Rect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.kashif.cameraK.engine.WorkerDispatcher
import com.kashif.cameraK.frames.FrameSpec
import com.kashif.cameraK.frames.imageProxy
import com.kashif.cameraK.utils.CameraKPlatformApi
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The multiplexer runs each consumer analyzer on the analyzer executor. A consumer that throws must
 * not take the frame callback down with it (on Android an exception there kills the host), must not
 * keep the other consumers from the frame, and must not leave the frame open: CameraX delivers no
 * further frames until it is closed.
 *
 * The cases run on FramePipeline's multiplexer (§12.4 A4'): the two containment cases on both the
 * synchronous and the pooled path, and the close and buffer cases on the proxy a synchronous
 * registration gets, each with its own read-only planes. (The 1.x-consumer twins went with the
 * facade's registerImageAnalyzer in C1'.)
 * Robolectric runs the class because the frames read the proxy's crop rect, an android.graphics.Rect.
 */
@RunWith(RobolectricTestRunner::class)
class MultiplexingAnalyzerTest {
    @Test
    fun throwingAnalyzer_isContainedAndTheOthersStillReceiveTheFrame_onTheSynchronousPath() {
        val frame = CountingFrame()
        val pipeline = pipeline()
        var secondSawFrame = false
        pipeline.register(FrameSpec(synchronous = true)) { throw IllegalStateException("consumer failed") }
        pipeline.register(FrameSpec(synchronous = true)) { secondSawFrame = true }

        pipeline.multiplexer.analyze(frame.proxy)

        assertEquals(true, secondSawFrame, "the analyzer after the throwing one never saw the frame")
        assertEquals(1, frame.closes, "the frame must be closed exactly once")
    }

    @Test
    fun throwingAnalyzer_isContainedAndTheOthersStillReceiveTheFrame_onThePooledPath() {
        val frame = CountingFrame()
        val pipeline = pipeline()
        var secondSawFrame = false
        pipeline.register { throw IllegalStateException("consumer failed") }
        pipeline.register { secondSawFrame = true }

        pipeline.multiplexer.analyze(frame.proxy)

        assertEquals(true, secondSawFrame, "the analyzer after the throwing one never saw the frame")
        assertEquals(1, frame.closes, "the frame must be closed exactly once")
    }

    @Test
    fun singleThrowingAnalyzer_isContainedAndItsFrameIsClosed_onTheSynchronousPath() {
        val pipeline = pipeline()
        var calls = 0
        pipeline.register(FrameSpec(synchronous = true)) {
            calls++
            throw IllegalStateException("consumer failed")
        }
        val first = CountingFrame()
        val next = CountingFrame()

        pipeline.multiplexer.analyze(first.proxy)
        pipeline.multiplexer.analyze(next.proxy)

        assertEquals(1, first.closes, "a throwing analyzer left the frame open, stalling the stream")
        assertEquals(2, calls, "the throwing analyzer was not released for the next frame")
    }

    @Test
    fun singleThrowingAnalyzer_isContainedAndItsFrameIsClosed_onThePooledPath() {
        val pipeline = pipeline()
        var calls = 0
        pipeline.register {
            calls++
            throw IllegalStateException("consumer failed")
        }
        val first = CountingFrame()
        val next = CountingFrame()

        pipeline.multiplexer.analyze(first.proxy)
        pipeline.multiplexer.analyze(next.proxy)

        assertEquals(1, first.closes, "a throwing analyzer left the frame open, stalling the stream")
        assertEquals(2, calls, "the throwing analyzer's slot was not released for the next frame")
    }

    @Test
    fun analyzerThatClosesThenThrows_doesNotCloseTheFrameEarly_onTheSynchronousPath() {
        val frame = CountingFrame()
        val pipeline = pipeline()
        var closesSeenBySecond = -1
        pipeline.register(FrameSpec(synchronous = true)) {
            it.imageProxy?.close()
            throw IllegalStateException("consumer failed after closing")
        }
        pipeline.register(FrameSpec(synchronous = true)) { closesSeenBySecond = frame.closes }

        pipeline.multiplexer.analyze(frame.proxy)

        assertEquals(0, closesSeenBySecond, "the frame was closed while another analyzer still held it")
        assertEquals(1, frame.closes)
    }

    @Test
    fun analyzerThatReadsAPlane_leavesItsPositionAsItWasForTheNextAnalyzer_onTheSynchronousPath() {
        val frame = CountingFrame(luma = byteArrayOf(10, 20, 30, 40))
        val pipeline = pipeline()
        var positionSeenBySecond = -1
        var bytesSeenBySecond = ByteArray(0)
        pipeline.register(FrameSpec(synchronous = true)) {
            // A bulk read, as a decoder copying the Y plane does: it moves the position.
            checkNotNull(it.imageProxy).planes[0].buffer.get(ByteArray(4))
        }
        pipeline.register(FrameSpec(synchronous = true)) {
            val buffer = checkNotNull(it.imageProxy).planes[0].buffer
            positionSeenBySecond = buffer.position()
            bytesSeenBySecond = ByteArray(buffer.remaining()).also { bytes -> buffer.get(bytes) }
        }

        pipeline.multiplexer.analyze(frame.proxy)

        assertEquals(0, positionSeenBySecond, "the first analyzer's read moved the plane for the second")
        assertContentEquals(byteArrayOf(10, 20, 30, 40), bytesSeenBySecond)
    }

    @Test
    fun analyzerThatWritesToAPlane_cannotChangeThePixelsTheNextAnalyzerReads_onTheSynchronousPath() {
        val frame = CountingFrame(luma = byteArrayOf(10, 20, 30, 40))
        val pipeline = pipeline()
        var firstPixelSeenBySecond: Byte = -1
        pipeline.register(FrameSpec(synchronous = true)) { checkNotNull(it.imageProxy).planes[0].buffer.put(0, 99) }
        pipeline.register(FrameSpec(synchronous = true)) {
            firstPixelSeenBySecond = checkNotNull(it.imageProxy).planes[0].buffer.get(0)
        }

        pipeline.multiplexer.analyze(frame.proxy)

        assertEquals(10, firstPixelSeenBySecond, "one analyzer rewrote the frame another analyzer reads")
        assertEquals(1, frame.closes)
    }

    @Test
    fun analyzerReadingMultiByteValues_seesThePlaneInItsOwnByteOrder_onTheSynchronousPath() {
        val frame = CountingFrame(luma = byteArrayOf(0x04, 0x03, 0x02, 0x01), order = ByteOrder.LITTLE_ENDIAN)
        val pipeline = pipeline()
        var orderSeen: ByteOrder? = null
        var intSeen = 0
        pipeline.register(FrameSpec(synchronous = true)) {
            val buffer = checkNotNull(it.imageProxy).planes[0].buffer
            orderSeen = buffer.order()
            intSeen = buffer.getInt(0)
        }
        pipeline.register(FrameSpec(synchronous = true)) { }

        pipeline.multiplexer.analyze(frame.proxy)

        assertEquals(ByteOrder.LITTLE_ENDIAN, orderSeen, "the analyzer's view lost the plane's byte order")
        assertEquals(0x01020304, intSeen, "a 32-bit read through the analyzer's view came back byte-swapped")
    }

    // A pipeline whose asynchronous workers run each frame on the calling thread.
    private fun pipeline() = FramePipeline(
        FakeCameraXBackend(),
        Runnable::run,
        newWorker = { WorkerDispatcher(Dispatchers.Unconfined) },
    )
}

private class CountingFrame(luma: ByteArray = ByteArray(0), order: ByteOrder = ByteOrder.BIG_ENDIAN) {
    var closes = 0
        private set

    /** One Y plane over [luma], shared by every caller as CameraX's plane buffers are. */
    private val plane: ImageProxy.PlaneProxy = object : ImageProxy.PlaneProxy {
        private val buffer = ByteBuffer.wrap(luma).order(order)

        override fun getRowStride() = luma.size

        override fun getPixelStride() = 1

        override fun getBuffer(): ByteBuffer = buffer
    }

    private val info: ImageInfo = Proxy.newProxyInstance(
        ImageInfo::class.java.classLoader,
        arrayOf(ImageInfo::class.java),
    ) { self, method, args ->
        when (method.name) {
            "getRotationDegrees" -> 0
            "getTimestamp" -> 0L
            "hashCode" -> System.identityHashCode(self)
            "equals" -> self === args?.firstOrNull()
            else -> null
        }
    } as ImageInfo

    val proxy: ImageProxy = Proxy.newProxyInstance(
        ImageProxy::class.java.classLoader,
        arrayOf(ImageProxy::class.java),
    ) { self, method, args ->
        when (method.name) {
            "close" -> {
                closes++
                null
            }
            "getWidth", "getHeight", "getFormat" -> 0
            "getPlanes" -> arrayOf(plane)
            "getCropRect" -> Rect()
            "getImageInfo" -> info
            "hashCode" -> System.identityHashCode(self)
            "equals" -> self === args?.firstOrNull()
            "toString" -> "CountingFrame"
            else -> null
        }
    } as ImageProxy
}

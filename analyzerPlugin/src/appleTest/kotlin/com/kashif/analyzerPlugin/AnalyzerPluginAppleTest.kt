@file:OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class)

package com.kashif.analyzerPlugin

import com.kashif.cameraK.frames.pixelBuffer
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.TestFrame
import com.kashif.cameraK.testing.fakeCameraKStateHolder
import com.kashif.cameraK.utils.CameraKPlatformApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.UIKit.UIImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AnalyzerPluginAppleTest {
    /**
     * The plugin gets frames from the holder's frame source and nothing else: given only a holder,
     * with no controller or capture session to add an output to, it encodes the frames the source
     * delivers (ios-config-queue, analyzer half).
     */
    @Test
    fun addsNoOutputOfItsOwn() = runTest {
        val engine = FakeCameraEngine()
        val plugin = AnalyzerPlugin(AnalyzerConfig(), { CoreImageFrameEncoder(it, copyingRenderer) })
        val holder = fakeCameraKStateHolder(fake = engine, plugins = listOf(plugin))
        holder.start()
        runCurrent()
        assertIs<CameraKState.Ready>(holder.cameraState.value)
        val results = mutableListOf<EncodedFrame>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { plugin.results.collect { results += it } }
        runCurrent()

        engine.fakeFrames.emit(TestFrame.luma(64, 48, ByteArray(64 * 48) { 0x60 }))

        val encoded = assertNotNull(results.singleOrNull(), "the frame source's frame was not encoded: ${results.size}")
        assertEquals(64 to 48, decodedSize(encoded.bytes))
    }

    @OptIn(CameraKPlatformApi::class)
    @Test
    fun encodesAFrameWhosePixelBufferIsNull() {
        val frame = TestFrame.luma(40, 30, ByteArray(40 * 30) { 0x30 }, rotationDegrees = 90, timestampNanos = 9L)
        assertNull(frame.pixelBuffer, "a test frame has no pixel buffer")

        val encoded = assertNotNull(CoreImageFrameEncoder(AnalyzerConfig(), copyingRenderer).encode(frame))

        assertEquals(40 to 30, decodedSize(encoded.bytes))
        assertEquals(40 to 30, encoded.width to encoded.height)
        assertEquals(90, encoded.rotationDegrees)
        assertEquals(9L, encoded.timestampNanos)
    }

    @Test
    fun anRgbaFrameIsEncodedThroughAnOwnBgraBuffer() {
        val rgba = ByteArray(16 * 8 * 4) { if (it % 4 == 3) -1 else 0x55 }

        val encoded =
            assertNotNull(CoreImageFrameEncoder(AnalyzerConfig(), copyingRenderer).encode(TestFrame.rgba(16, 8, rgba)))

        assertEquals(16 to 8, decodedSize(encoded.bytes))
    }

    private fun decodedSize(jpeg: ByteArray): Pair<Int, Int> {
        val data = jpeg.usePinned { NSData.dataWithBytes(it.addressOf(0), jpeg.size.toULong()) }
        val image = assertNotNull(UIImage.imageWithData(data), "the payload is not an encoded image")
        return image.size.useContents { width.toInt() to height.toInt() }
    }
}

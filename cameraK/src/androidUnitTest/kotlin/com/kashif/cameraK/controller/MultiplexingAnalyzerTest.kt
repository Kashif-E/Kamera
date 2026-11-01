package com.kashif.cameraK.controller

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals

/**
 * The multiplexer runs each consumer analyzer on the analyzer executor. A consumer that throws must
 * not take the frame callback down with it (on Android an exception there kills the host), must not
 * keep the other consumers from the frame, and must not leave the frame open: CameraX delivers no
 * further frames until it is closed.
 */
class MultiplexingAnalyzerTest {
    @Test
    fun throwingAnalyzer_isContainedAndTheOthersStillReceiveTheFrame() {
        val frame = CountingFrame()
        var secondSawFrame = false
        val analyzer = MultiplexingAnalyzer(
            listOf(
                ImageAnalysis.Analyzer { throw IllegalStateException("consumer failed") },
                ImageAnalysis.Analyzer { proxy ->
                    secondSawFrame = true
                    proxy.close()
                },
            ),
        )

        analyzer.analyze(frame.proxy)

        assertEquals(true, secondSawFrame, "the analyzer after the throwing one never saw the frame")
        assertEquals(1, frame.closes, "the frame must be closed exactly once")
    }

    @Test
    fun singleThrowingAnalyzer_isContainedAndItsFrameIsClosed() {
        val frame = CountingFrame()
        val analyzer =
            MultiplexingAnalyzer(listOf(ImageAnalysis.Analyzer { throw IllegalStateException("consumer failed") }))

        analyzer.analyze(frame.proxy)

        assertEquals(1, frame.closes, "a throwing analyzer left the frame open, stalling the stream")
    }

    @Test
    fun analyzerThatClosesThenThrows_doesNotCloseTheFrameEarly() {
        val frame = CountingFrame()
        var closesSeenBySecond = -1
        val analyzer = MultiplexingAnalyzer(
            listOf(
                ImageAnalysis.Analyzer { proxy ->
                    proxy.close()
                    throw IllegalStateException("consumer failed after closing")
                },
                ImageAnalysis.Analyzer { proxy ->
                    closesSeenBySecond = frame.closes
                    proxy.close()
                },
            ),
        )

        analyzer.analyze(frame.proxy)

        assertEquals(0, closesSeenBySecond, "the frame was closed while another analyzer still held it")
        assertEquals(1, frame.closes)
    }
}

private class CountingFrame {
    var closes = 0
        private set

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
            "hashCode" -> System.identityHashCode(self)
            "equals" -> self === args?.firstOrNull()
            "toString" -> "CountingFrame"
            else -> null
        }
    } as ImageProxy
}

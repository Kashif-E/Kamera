@file:OptIn(ExperimentalCoroutinesApi::class)

package org.company.app

import com.kashif.analyzerplugin.AnalyzerPlugin
import com.kashif.analyzerplugin.EncodedFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Sample's analyzer keeps only a summary of the latest frame, never the JPEG, so the camera screen
 * does not hold or recompose on every encoded frame.
 */
class AnalyzerFeatureTest {
    @Test
    fun theLatestFrameIsKeptAsASummary() = runTest {
        val frames = MutableSharedFlow<EncodedFrame>()
        val analyzer = AnalyzerFeature(AnalyzerPlugin(), frames)
        backgroundScope.launch { analyzer.summarize() }
        runCurrent()
        assertNull(analyzer.summary.value, "a summary before any frame")

        frames.emit(EncodedFrame(ByteArray(1234), width = 640, height = 480, rotationDegrees = 90, timestampNanos = 1))
        runCurrent()
        assertEquals(
            AnalyzedFrameSummary(frames = 1, width = 640, height = 480, jpegBytes = 1234),
            analyzer.summary.value,
        )

        frames.emit(EncodedFrame(ByteArray(2048), width = 320, height = 240, rotationDegrees = 0, timestampNanos = 2))
        runCurrent()
        assertEquals(
            AnalyzedFrameSummary(frames = 2, width = 320, height = 240, jpegBytes = 2048),
            analyzer.summary.value,
        )
    }
}

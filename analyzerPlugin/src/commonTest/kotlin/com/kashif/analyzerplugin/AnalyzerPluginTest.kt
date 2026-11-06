@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kashif.analyzerplugin

import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.frames.CameraFrame
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.plugin.PluginStatus
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.TestFrame
import com.kashif.cameraK.testing.fakeCameraKStateHolder
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The analyzer over a fake engine and its frame source. Frames are emitted at least 1/maxFps apart
 * by their timestamps, so the tests hold whether or not the fake applies maxFps.
 */
class AnalyzerPluginTest {
    @AfterTest
    fun restoreLogger() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    /** An encoder that records the frames it is given and encodes each as its own timestamp. */
    private class RecordingEncoder : FrameEncoder {
        val frames = mutableListOf<CameraFrame>()
        var failure: Exception? = null

        override fun encode(frame: CameraFrame): EncodedFrame {
            frames += frame
            failure?.let { throw it }
            return EncodedFrame(
                byteArrayOf(1, 2, 3),
                frame.width,
                frame.height,
                frame.rotationDegrees,
                frame.timestampNanos,
            )
        }
    }

    private class Session(
        val holder: CameraKStateHolder,
        val engine: FakeCameraEngine,
        val plugin: AnalyzerPlugin,
        val encoder: RecordingEncoder,
        val events: List<CameraKEvent>,
    ) {
        private var next = 0L

        /** Emits a frame one second after the last, inside any maxFps up to 1. */
        fun emit(width: Int = 4, height: Int = 2, rotation: Int = 0) {
            val frame = TestFrame.luma(
                width,
                height,
                ByteArray(width * height),
                rotationDegrees = rotation,
                timestampNanos = next,
            )
            next += ONE_SECOND_NANOS
            engine.fakeFrames.emit(frame)
        }
    }

    private fun TestScope.session(
        config: CameraConfiguration = CameraConfiguration(),
        acceptedFormats: Set<FrameFormat> = AnalyzerPlugin.ENCODED_FORMATS,
        start: Boolean = true,
    ): Session {
        val encoder = RecordingEncoder()
        val plugin = AnalyzerPlugin(AnalyzerConfig(), { encoder }, acceptedFormats)
        val engine = FakeCameraEngine()
        val holder = fakeCameraKStateHolder(config = config, fake = engine, plugins = listOf(plugin))
        val events = mutableListOf<CameraKEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { holder.events.collect { events += it } }
        if (start) {
            holder.start()
            runCurrent()
            assertIs<CameraKState.Ready>(holder.cameraState.value, "state is ${holder.cameraState.value}")
        }
        return Session(holder, engine, plugin, encoder, events)
    }

    private fun TestScope.collect(plugin: AnalyzerPlugin): Pair<List<EncodedFrame>, Job> {
        val results = mutableListOf<EncodedFrame>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            plugin.results.collect {
                results +=
                    it
            }
        }
        runCurrent()
        return results to job
    }

    @Test
    fun encodesOnlyWhileResultsSubscribed() = runTest {
        val s = session()

        s.emit()
        assertTrue(s.encoder.frames.isEmpty(), "a frame was encoded with no collector of results")
        assertEquals(PluginStatus.Idle, s.plugin.status.value, "with no collector the analyzer has nothing to do")

        val (results, collector) = collect(s.plugin)
        s.emit(width = 6, height = 4, rotation = 90)
        assertEquals(1, s.encoder.frames.size, "the frame emitted while results had a collector")
        val encoded = results.single()
        assertEquals(6 to 4, encoded.width to encoded.height, "the encoded size")
        assertEquals(90, encoded.rotationDegrees, "the frame's rotation")
        assertEquals(ONE_SECOND_NANOS, encoded.timestampNanos, "the frame's timestamp")
        assertEquals(PluginStatus.Running, s.plugin.status.value)

        collector.cancel()
        runCurrent()
        s.emit()
        assertEquals(1, s.encoder.frames.size, "a frame was encoded after the last collector left")
        assertEquals(PluginStatus.Idle, s.plugin.status.value)
    }

    @Test
    fun detachClosesTheRegistration() = runTest {
        val s = session()
        val (results, _) = collect(s.plugin)
        s.emit()
        assertEquals(1, results.size)

        s.holder.detachPlugin(s.plugin)
        runCurrent()
        s.emit()

        assertEquals(1, s.encoder.frames.size, "a detached analyzer still received a frame")
        assertEquals(PluginStatus.Idle, s.plugin.status.value)
    }

    @Test
    fun noSessionNoFrames() = runTest {
        val s = session(start = false)
        val (results, _) = collect(s.plugin)

        s.emit()
        s.holder.start()
        runCurrent()
        s.holder.stop()
        runCurrent()
        s.emit()

        assertTrue(s.encoder.frames.isEmpty(), "a frame was encoded outside a Ready session")
        assertTrue(results.isEmpty())
        assertEquals(PluginStatus.Idle, s.plugin.status.value)
    }

    @Test
    fun pauseReleasesTheRegistration() = runTest {
        val s = session()
        val (results, _) = collect(s.plugin)

        s.plugin.pause()
        runCurrent()
        s.emit()
        assertTrue(s.encoder.frames.isEmpty(), "a paused analyzer encoded a frame")
        assertEquals(PluginStatus.Paused, s.plugin.status.value)

        s.plugin.resume()
        runCurrent()
        s.emit()
        assertEquals(1, results.size, "the analyzer did not encode again after resume")
        assertEquals(PluginStatus.Running, s.plugin.status.value)
    }

    @Test
    fun aRejectedRegistrationMarksFailed() = runTest {
        val s = session(
            config = CameraConfiguration { analysisFormat = FrameFormat.RGBA_8888 },
            acceptedFormats = setOf(FrameFormat.YUV_420),
        )

        collect(s.plugin)

        val failed = assertIs<PluginStatus.Failed>(s.plugin.status.value, "status ${s.plugin.status.value}")
        val error = assertIs<CameraKError.Unsupported>(failed.error)
        assertEquals(CameraFeature.ANALYSIS_FORMAT, error.feature)
        val reported = s.events.filterIsInstance<CameraKEvent.PluginFailed>().single()
        assertEquals("AnalyzerPlugin", reported.pluginId)
        assertSame(failed.error, reported.error)
    }

    @Test
    fun throwingEncoderMarksFailed() = runTest {
        val logged = mutableListOf<Throwable?>()
        CameraKLogger.enabled = true
        CameraKLogger.sink = { _, _, _, throwable -> logged += throwable }
        val s = session()
        val failure = IllegalStateException("encoder broke")
        s.encoder.failure = failure
        val (results, _) = collect(s.plugin)

        s.emit()
        runCurrent()
        s.emit()

        assertTrue(results.isEmpty())
        assertEquals(1, s.encoder.frames.size, "a failed analyzer kept encoding")
        val failed = assertIs<PluginStatus.Failed>(s.plugin.status.value, "status ${s.plugin.status.value}")
        assertSame(failure, assertIs<CameraKError.Internal>(failed.error).cause)
        assertEquals(
            1,
            s.events.filterIsInstance<CameraKEvent.PluginFailed>().size,
            "the failure was reported more than once",
        )
        assertTrue(failure in logged, "the encoder's throwable was not logged")
    }

    @Test
    fun frameSpecIsAsynchronousRetainsNothingAndAcceptsBothFormats() {
        val spec = AnalyzerConfig { maxFps = 3f }.frameSpec(AnalyzerPlugin.ENCODED_FORMATS)

        assertEquals(3f, spec.maxFps)
        assertEquals(0, spec.maxRetained, "the analyzer encodes inside analyze and retains no frame")
        assertEquals(false, spec.synchronous, "a slow encode must not hold up the producer")
        assertEquals(setOf(FrameFormat.YUV_420, FrameFormat.RGBA_8888), spec.acceptedFormats)
    }

    @Test
    fun aFrameWithoutAnEncodingIsSkippedAndLogged() = runTest {
        val messages = mutableListOf<String>()
        CameraKLogger.enabled = true
        CameraKLogger.sink = { _, _, message, _ -> messages += message }
        val plugin = AnalyzerPlugin(AnalyzerConfig(), { FrameEncoder { null } })
        val engine = FakeCameraEngine()
        val holder = fakeCameraKStateHolder(fake = engine, plugins = listOf(plugin))
        holder.start()
        runCurrent()
        val (results, _) = collect(plugin)

        engine.fakeFrames.emit(TestFrame.luma(2, 2, ByteArray(4)))

        assertTrue(results.isEmpty())
        assertEquals(PluginStatus.Running, plugin.status.value, "a frame the platform could not encode is no failure")
        assertTrue(messages.any { "skipped" in it }, "the skipped frame was not logged: $messages")
    }

    private companion object {
        const val ONE_SECOND_NANOS = 1_000_000_000L
    }
}

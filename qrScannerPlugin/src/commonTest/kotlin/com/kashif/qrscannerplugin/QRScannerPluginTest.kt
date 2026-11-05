package com.kashif.qrscannerplugin

import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FrameRect
import com.kashif.cameraK.plugin.PluginStatus
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.testing.TestFrame
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The QR plugin over a fake decoder: one registration per Ready session through the holder's frame
 * source, the common luma handed to the decoder, one dedupe policy, and `status`/`results` as the
 * only outputs. Frames are emitted at least 1 / maxFps apart, so each holds on a gated source too.
 */
class QRScannerPluginTest {
    private val logs = LoggedProblems()

    @AfterTest
    fun restoreLogger() = logs.restore()

    @Test
    fun framesInGiveBarcodesOut() = runTest {
        val decoders = FakeDecoders { listOf(qr("HELLO")) }
        val plugin = QRScannerPlugin(QRScannerConfig(), decoders)
        val results = collectResults(plugin)
        val s = qrSession(plugin)
        startReady(s)

        s.fake.fakeFrames.emit(blankFrame(0))

        assertEquals(listOf(qr("HELLO")), results)
        assertEquals(1, decoders.images.size, "decodes")
    }

    @Test
    fun registersAsynchronouslyForBothFormatsRetainingNone() = runTest {
        val plugin = QRScannerPlugin(QRScannerConfig(), FakeDecoders())
        val s = qrSession(plugin)
        startReady(s)

        val spec = s.frames.registrations.single().spec
        assertEquals(false, spec.synchronous, "synchronous")
        assertEquals(0, spec.maxRetained, "maxRetained")
        assertEquals(setOf(FrameFormat.YUV_420, FrameFormat.RGBA_8888), spec.acceptedFormats, "acceptedFormats")
    }

    @Test
    fun maxFpsBoundsDecodes() = runTest {
        val decoders = FakeDecoders()
        val plugin = QRScannerPlugin(QRScannerConfig { maxFps = 3f }, decoders)
        val s = qrSession(plugin)
        startReady(s)

        // The shared gate drops frames inside 1 / maxFps (AnalyzerGateTest); the plugin keeps no
        // throttle of its own, so every frame the source admits is decoded.
        repeat(6) { s.fake.fakeFrames.emit(blankFrame(it)) }

        assertEquals(3f, s.frames.registrations.single().spec.maxFps, "the registration's maxFps")
        assertEquals(6, decoders.images.size, "decodes of 6 admitted frames")
    }

    @Test
    fun byDefaultTheSourceAdmitsFiveFramesASecond() = runTest {
        val plugin = QRScannerPlugin(QRScannerConfig(), FakeDecoders())
        val s = qrSession(plugin)
        startReady(s)

        assertEquals(5f, s.frames.registrations.single().spec.maxFps)
    }

    @Test
    fun pauseReleasesTheRegistration() = runTest {
        val decoders = FakeDecoders()
        val plugin = QRScannerPlugin(QRScannerConfig(), decoders)
        val s = qrSession(plugin)
        startReady(s)
        assertEquals(1, s.frames.open.size, "registrations while scanning")

        plugin.pause()
        runCurrent()
        s.fake.fakeFrames.emit(blankFrame(0))

        assertTrue(s.frames.registrations.single().closed, "the paused plugin still holds its registration")
        assertEquals(0, decoders.images.size, "frames decoded while paused")
        assertEquals(PluginStatus.Paused, plugin.status.value)
    }

    @Test
    fun aPauseBeforeASessionSurvivesIt() = runTest {
        val decoders = FakeDecoders()
        val plugin = QRScannerPlugin(QRScannerConfig(), decoders)
        val s = qrSession(plugin)
        plugin.pause()

        startReady(s)
        s.fake.fakeFrames.emit(blankFrame(0))
        s.holder.stop()
        runCurrent()
        startReady(s)

        assertEquals(0, s.frames.registrations.size, "registrations made while paused")
        assertEquals(0, decoders.images.size, "frames decoded while paused")
        assertEquals(PluginStatus.Paused, plugin.status.value)
    }

    @Test
    fun resumeAfterPauseRegistersAgain() = runTest {
        val decoders = FakeDecoders { listOf(qr("HELLO")) }
        val plugin = QRScannerPlugin(QRScannerConfig(), decoders)
        val results = collectResults(plugin)
        val s = qrSession(plugin)
        startReady(s)

        plugin.pause()
        runCurrent()
        plugin.resume()
        runCurrent()
        s.fake.fakeFrames.emit(blankFrame(0))

        assertEquals(listOf(true, false), s.frames.registrations.map { it.closed }, "closed, per registration")
        assertEquals(listOf(qr("HELLO")), results)
        assertEquals(PluginStatus.Running, plugin.status.value)
    }

    @Test
    fun resumeBeforeASessionRegistersNothing() = runTest {
        val decoders = FakeDecoders { listOf(qr("HELLO")) }
        val plugin = QRScannerPlugin(QRScannerConfig(), decoders)
        val results = collectResults(plugin)
        val s = qrSession(plugin)

        plugin.resume()
        runCurrent()

        assertEquals(0, s.frames.registrations.size, "registrations without a session")
        assertTrue(results.isEmpty(), "results without a session: $results")
        assertEquals(PluginStatus.Idle, plugin.status.value)
    }

    @Test
    fun detachClosesTheRegistration() = runTest {
        val decoders = FakeDecoders()
        val plugin = QRScannerPlugin(QRScannerConfig(), decoders)
        val s = qrSession(plugin)
        startReady(s)

        s.holder.detachPlugin(plugin)
        runCurrent()
        s.fake.fakeFrames.emit(blankFrame(0))

        assertTrue(s.frames.registrations.single().closed, "the detached plugin still holds its registration")
        assertEquals(0, decoders.images.size, "frames decoded after detach")
        assertEquals(PluginStatus.Idle, plugin.status.value)
    }

    @Test
    fun leavingReadyClosesTheRegistrationAndTheNextSessionRegistersAgain() = runTest {
        val plugin = QRScannerPlugin(QRScannerConfig(), FakeDecoders())
        val s = qrSession(plugin)
        startReady(s)

        s.holder.stop()
        runCurrent()
        assertEquals(PluginStatus.Idle, plugin.status.value, "status with no session")
        startReady(s)

        assertEquals(listOf(true, false), s.frames.registrations.map { it.closed }, "closed, per registration")
        assertEquals(PluginStatus.Running, plugin.status.value)
    }

    @Test
    fun aRegistrationWaitingForFramesShowsPaused() = runTest {
        val plugin = QRScannerPlugin(QRScannerConfig(), FakeDecoders())
        val s = qrSession(plugin)
        s.frames.inactive = true
        startReady(s)

        assertEquals(1, s.frames.open.size, "registrations")
        assertEquals(PluginStatus.Paused, plugin.status.value)
    }

    @Test
    fun throwingDecoderMarksFailed() = runTest {
        logs.install()
        val failure = IllegalStateException("decoder bug")
        val decoders = FakeDecoders { throw failure }
        val plugin = QRScannerPlugin(QRScannerConfig(), decoders)
        val s = qrSession(plugin)
        startReady(s)

        s.fake.fakeFrames.emit(blankFrame(0))
        runCurrent()
        s.fake.fakeFrames.emit(blankFrame(1))
        runCurrent()

        val status = assertIs<PluginStatus.Failed>(plugin.status.value)
        assertSame(failure, assertIs<CameraKError.Internal>(status.error).cause, "the failure's cause")
        assertEquals(1, s.events.filterIsInstance<CameraKEvent.PluginFailed>().size, "PluginFailed events")
        assertEquals(1, decoders.images.size, "decodes after the decoder threw")
        assertTrue(s.frames.registrations.single().closed, "the failed plugin still holds its registration")
        assertTrue(
            logs.entries.any {
                it.second === failure
            },
            "the decoder's throwable was not logged: ${logs.entries}",
        )
    }

    @Test
    fun aRejectedRegistrationMarksFailed() = runTest {
        val rejection = CameraKError.Unsupported(CameraFeature.ANALYSIS_FORMAT)
        val plugin = QRScannerPlugin(QRScannerConfig(), FakeDecoders())
        val s = qrSession(plugin)
        s.frames.rejectWith = rejection
        startReady(s)

        val status = assertIs<PluginStatus.Failed>(plugin.status.value)
        assertSame(rejection, status.error)
        assertEquals(1, s.events.filterIsInstance<CameraKEvent.PluginFailed>().size, "PluginFailed events")
        assertEquals(1, s.frames.registrations.size, "registrations after the rejection")
    }

    @Test
    fun framesWithNoReadableCodeAreNoFailureAndLogNothing() = runTest {
        logs.install()
        val decoders = FakeDecoders { emptyList() }
        val plugin = QRScannerPlugin(QRScannerConfig(), decoders)
        val results = collectResults(plugin)
        val s = qrSession(plugin)
        startReady(s)

        repeat(5) { s.fake.fakeFrames.emit(blankFrame(it)) }
        runCurrent()

        assertEquals(5, decoders.images.size, "decodes")
        assertTrue(results.isEmpty(), "results: $results")
        assertEquals(PluginStatus.Running, plugin.status.value)
        assertTrue(logs.entries.isEmpty(), "frames with no code were logged: ${logs.entries}")
    }

    @Test
    fun dedupeWithinTheWindow() = runTest {
        var next = listOf(qr("A"))
        val plugin = QRScannerPlugin(QRScannerConfig(), FakeDecoders { next })
        val results = collectResults(plugin)
        val s = qrSession(plugin)
        startReady(s)

        // Frames 250 ms apart: A at 0, 250 and 500 ms, B at 750 ms, A at 1000 and 1250 ms.
        for (index in 0..5) {
            next = listOf(qr(if (index == 3) "B" else "A"))
            s.fake.fakeFrames.emit(blankFrame(index))
        }

        assertEquals(listOf("A", "B", "A"), results.map { it.rawValue }, "reported with a 1 s window")
    }

    @Test
    fun configuredFormatsReachTheDecoder() = runTest {
        val decoders = FakeDecoders()
        val formats = setOf(BarcodeFormat.QR_CODE, BarcodeFormat.CODE_128, BarcodeFormat.EAN_13)
        val plugin = QRScannerPlugin(QRScannerConfig { this.formats = formats }, decoders)
        val s = qrSession(plugin)
        startReady(s)

        assertEquals(listOf(formats), decoders.created)
    }

    @Test
    fun regionLimitsTheDecodeArea() = runTest {
        val decoders = FakeDecoders()
        val plugin = QRScannerPlugin(QRScannerConfig { region = FrameRect(2, 2, 6, 6) }, decoders)
        val s = qrSession(plugin)
        startReady(s)

        s.fake.fakeFrames.emit(blankFrame(0))
        s.fake.fakeFrames.emit(blankFrame(1, cropRect = FrameRect(0, 0, 4, 8)))
        s.fake.fakeFrames.emit(blankFrame(2, cropRect = FrameRect(6, 0, 8, 8)))

        assertEquals(
            listOf(listOf(2, 2, 6, 6), listOf(2, 2, 4, 6)),
            decoders.images.map { it.area },
            "decode areas; the third frame's crop rect misses the region, so it is not decoded",
        )
    }

    @Test
    fun decodingIsConfinedToTheCropRect() = runTest {
        val decoders = FakeDecoders()
        val plugin = QRScannerPlugin(QRScannerConfig(), decoders)
        val s = qrSession(plugin)
        startReady(s)

        s.fake.fakeFrames.emit(blankFrame(0, width = 48, height = 32, cropRect = FrameRect(8, 4, 40, 28)))

        assertEquals(listOf(8, 4, 40, 28), decoders.images.single().area)
    }

    @Test
    fun theFramesRotationReachesTheDecoder() = runTest {
        val decoders = FakeDecoders()
        val plugin = QRScannerPlugin(QRScannerConfig(), decoders)
        val s = qrSession(plugin)
        startReady(s)

        s.fake.fakeFrames.emit(TestFrame.luma(4, 2, ByteArray(8), rotationDegrees = 90))

        assertEquals(90, decoders.images.single().rotationDegrees)
    }

    @Test
    fun framesOfTheSameSizeReuseOneLumaBuffer() = runTest {
        val decoders = FakeDecoders()
        val plugin = QRScannerPlugin(QRScannerConfig(), decoders)
        val s = qrSession(plugin)
        startReady(s)

        repeat(3) { s.fake.fakeFrames.emit(blankFrame(it)) }

        val buffers = decoders.images.map { it.buffer }
        assertEquals(3, buffers.size, "decodes")
        assertSame(buffers[0], buffers[1], "the second frame allocated a new luma buffer")
        assertSame(buffers[0], buffers[2], "the third frame allocated a new luma buffer")
    }

    @Test
    fun theLumaBufferHoldsTheFramesLumaWithoutRowPadding() = runTest {
        val decoders = FakeDecoders()
        val plugin = QRScannerPlugin(QRScannerConfig(), decoders)
        val s = qrSession(plugin)
        startReady(s)
        val width = 6
        val height = 4
        val rowStride = 8
        val bytes = ByteArray(rowStride * height) { (it * 7 + 3).toByte() }

        s.fake.fakeFrames.emit(TestFrame.luma(width, height, bytes, rowStride = rowStride))

        val expected = ByteArray(width * height) { bytes[(it / width) * rowStride + it % width] }
        val seen = decoders.images.single()
        assertEquals(width to height, seen.width to seen.height, "size")
        assertContentEquals(expected, seen.luma)
    }

    @Test
    fun anRgbaFrameReachesTheDecoderAsLuma() = runTest {
        val decoders = FakeDecoders()
        val plugin = QRScannerPlugin(QRScannerConfig(), decoders)
        val s = qrSession(plugin, CameraConfiguration(analysisFormat = FrameFormat.RGBA_8888))
        startReady(s)
        // One white pixel, then one black one.
        val rgba = byteArrayOf(-1, -1, -1, -1, 0, 0, 0, -1)

        s.fake.fakeFrames.emit(TestFrame.rgba(2, 1, rgba))

        assertContentEquals(byteArrayOf(-1, 0), decoders.images.single().luma)
    }

    @Test
    fun aSlowResultsCollectorNeverSuspendsTheDecoder() = runTest {
        var count = 0
        val decoders = FakeDecoders { listOf(qr("code ${count++}")) }
        val plugin = QRScannerPlugin(QRScannerConfig(), decoders)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            plugin.results.collect { awaitCancellation() }
        }
        val fast = collectResults(plugin)
        val s = qrSession(plugin)
        startReady(s)

        repeat(20) { s.fake.fakeFrames.emit(blankFrame(it)) }
        runCurrent()

        assertEquals(20, decoders.images.size, "decodes while a collector is stuck")
        assertEquals(20, fast.size, "results a second collector received")
        assertTrue(s.events.isEmpty(), "the plugin emitted core events: ${s.events}")
    }

    @Test
    fun statusIsIdleUntilASessionThenRunning() = runTest {
        val plugin = QRScannerPlugin(QRScannerConfig(), FakeDecoders())
        val s = qrSession(plugin)
        assertEquals(PluginStatus.Idle, plugin.status.value, "before a session")

        startReady(s)

        assertEquals(PluginStatus.Running, plugin.status.value, "in a session")
    }
}

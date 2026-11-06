package com.kashif.ocrPlugin

import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FrameRect
import com.kashif.cameraK.frames.copyLuma
import com.kashif.cameraK.plugin.PluginStatus
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.testing.TestFrame
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The OCR plugin's §9.2 shape over a fake engine and fake recognizers: live recognition through the
 * shared frame source, its rate, region, rotation and formats, its lifecycle and failures, and the
 * one-shot recognize contract that holds on every platform.
 */
class OcrPluginTest {
    private val logged = mutableListOf<Pair<String, Throwable?>>()

    @BeforeTest
    fun captureLogs() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, throwable ->
            if (level == CameraKLogger.Level.ERROR) logged += message to throwable
        }
    }

    @AfterTest
    fun restoreLogger() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun recognizeFailureIsFailureOnEveryPlatform() = runTest {
        val failure = IllegalStateException("the model could not run")
        val recognizers = FakeRecognizers().apply { stillAnswer = { throw failure } }
        val plugin = OcrPlugin(OcrConfig(), recognizers, StandardTestDispatcher(testScheduler))

        val result = plugin.recognize(capture())

        val error = assertIs<CameraKError.Internal>(assertIs<CameraKResult.Failure>(result).error)
        assertSame(failure, error.cause)
        assertEquals(1, logged.size, "error lines for one failed recognition: $logged")
        assertSame(failure, logged.single().second)
        assertEquals(listOf(1), recognizers.stills.map { it.closes }, "close count per still recognizer")
    }

    @Test
    fun oneShotRunsOffMainAndReleasesRecognizer() = runTest {
        val recognition = StandardTestDispatcher(testScheduler, name = "recognition")
        var ranOn: ContinuationInterceptor? = null
        val recognizers = FakeRecognizers().apply {
            stillAnswer = {
                ranOn = currentCoroutineContext()[ContinuationInterceptor]
                listOf(FoundBlock(FakeRecognizers.STILL_TEXT, FrameRect(1, 2, 3, 4), 0.5f))
            }
        }
        val plugin = OcrPlugin(OcrConfig(), recognizers, recognition)

        val result = plugin.recognize(capture("/captures/IMG_7.jpg"))

        val text = assertIs<CameraKResult.Success<RecognizedText>>(result).value
        assertEquals(
            RecognizedText(
                FakeRecognizers.STILL_TEXT,
                listOf(TextBlock(FakeRecognizers.STILL_TEXT, FrameRect(1, 2, 3, 4), 0.5f)),
                0L,
            ),
            text,
        )
        assertSame(recognition, ranOn, "the recognizer ran on the caller's dispatcher")
        assertEquals(listOf("/captures/IMG_7.jpg"), recognizers.stills.single().paths)
        assertEquals(1, recognizers.stills.single().closes, "close count after a success")

        recognizers.stillAnswer = { awaitCancellation() }
        val cancelled = launch { plugin.recognize(capture()) }
        runCurrent()
        cancelled.cancel()
        runCurrent()
        assertEquals(listOf(1, 1), recognizers.stills.map { it.closes }, "close count per recognizer after a cancel")
        assertTrue(logged.isEmpty(), "a cancelled recognition was logged as a failure: $logged")
    }

    @Test
    fun maxFpsBoundsRecognitions() = runTest {
        val recognizers = FakeRecognizers()
        val plugin = OcrPlugin(OcrConfig { maxFps = 5f }, recognizers)
        val session = ocrSession(plugin)
        val results = collectResults(plugin)
        startReady(session)

        oneSecondOfFrames().forEach(session.engine.fakeFrames::emit)

        // At 5 fps a frame is read once 200 ms have passed since the last one read: 0, 233, 467,
        // 700 and 933 ms.
        assertEquals(5, recognizers.seen.size, "frames read in one second of 30 fps at maxFps 5")
        assertEquals(
            listOf(0, 7, 14, 21, 28).map { it * FRAME_NANOS },
            results.map { it.timestampNanos },
            "the frames whose text was emitted",
        )
    }

    @Test
    fun byDefaultRecognitionsAreBoundedToTwoASecond() = runTest {
        val recognizers = FakeRecognizers()
        val session = ocrSession(OcrPlugin(OcrConfig(), recognizers))
        startReady(session)

        oneSecondOfFrames().forEach(session.engine.fakeFrames::emit)

        assertEquals(2, recognizers.seen.size, "frames read in one second of 30 fps at the default maxFps")
    }

    @Test
    fun orientationComesFromFrameRotation() = runTest {
        val recognizers = FakeRecognizers().apply {
            // Upright, the 4 x 2 frame is 2 wide and 4 high; this box is its top-left 1 x 2.
            liveAnswer = { listOf(FoundBlock(FakeRecognizers.TEXT, FrameRect(0, 0, 1, 2), null)) }
        }
        val plugin = OcrPlugin(OcrConfig(), recognizers)
        val session = ocrSession(plugin)
        val results = collectResults(plugin)
        startReady(session)

        session.engine.fakeFrames.emit(countingFrame(width = 4, height = 2, rotationDegrees = 90))

        assertEquals(90, recognizers.seen.single().rotationDegrees, "rotation handed to the recognizer")
        val bounds = results.single().blocks.single().bounds
        assertEquals(
            listOf(0, 1, 2, 2),
            listOfNotNull(bounds?.left, bounds?.top, bounds?.right, bounds?.bottom),
            "the upright box in frame pixels",
        )
    }

    @Test
    fun rgbaSessionReachesTheRecognizer() = runTest {
        val recognizers = FakeRecognizers()
        val plugin = OcrPlugin(OcrConfig(), recognizers)
        val session = ocrSession(plugin, FrameFormat.RGBA_8888)
        startReady(session)
        val rgba =
            byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 255.toByte(), 200.toByte(), 30, 60, 255.toByte())
        val frame = TestFrame.rgba(2, 1, rgba)
        val expectedLuma = ByteArray(2).also { frame.copyLuma(it) }.toList()

        session.engine.fakeFrames.emit(frame)

        val seen = recognizers.seen.single()
        assertEquals(expectedLuma, seen.luma, "the RGBA frame's luma")
        assertEquals(2 to 1, seen.width to seen.height)
        assertEquals(PluginStatus.Running, plugin.status.value)
    }

    @Test
    fun regionLimitsTheRecognitionArea() = runTest {
        val recognizers = FakeRecognizers().apply {
            liveAnswer = { listOf(FoundBlock(FakeRecognizers.TEXT, FrameRect(0, 0, 1, 1), null)) }
        }
        val plugin = OcrPlugin(OcrConfig { region = FrameRect(1, 1, 3, 3) }, recognizers)
        val session = ocrSession(plugin)
        val results = collectResults(plugin)
        startReady(session)

        session.engine.fakeFrames.emit(countingFrame(width = 4, height = 4))

        val seen = recognizers.seen.single()
        assertEquals(2 to 2, seen.width to seen.height, "size handed to the recognizer")
        assertEquals(listOf<Byte>(5, 6, 9, 10), seen.luma, "pixels handed to the recognizer")
        val bounds = results.single().blocks.single().bounds
        assertEquals(
            listOf(1, 1, 2, 2),
            listOfNotNull(bounds?.left, bounds?.top, bounds?.right, bounds?.bottom),
            "the region's box in frame pixels",
        )
    }

    @Test
    fun levelReachesTheRecognizer() = runTest {
        val recognizers = FakeRecognizers()
        val accurate = ocrSession(OcrPlugin(OcrConfig { level = OcrLevel.ACCURATE }, recognizers))
        startReady(accurate)
        val plugin = OcrPlugin(OcrConfig(), recognizers, StandardTestDispatcher(testScheduler))
        startReady(ocrSession(plugin))

        plugin.recognize(capture())
        plugin.recognize(capture(), OcrLevel.FAST)

        assertEquals(listOf(OcrLevel.ACCURATE, OcrLevel.FAST), recognizers.liveLevels, "live levels")
        assertEquals(listOf(OcrLevel.ACCURATE, OcrLevel.FAST), recognizers.stills.map { it.level }, "still levels")
    }

    @Test
    fun detachThenReattachRegistersAgain() = runTest {
        val recognizers = FakeRecognizers()
        val plugin = OcrPlugin(OcrConfig(), recognizers)
        val session = ocrSession(plugin)
        val results = collectResults(plugin)
        startReady(session)
        assertEquals(1, session.frames.open, "registrations while attached")

        session.holder.detachPlugin(plugin)
        runCurrent()
        assertEquals(0, session.frames.open, "registrations after detach")
        assertEquals(1, recognizers.liveCloses, "live recognizers closed after detach")
        assertEquals(PluginStatus.Idle, plugin.status.value)

        session.holder.attachPlugin(plugin)
        runCurrent()
        assertEquals(1, session.frames.open, "registrations after reattach")
        session.engine.fakeFrames.emit(countingFrame())
        assertEquals(listOf(FakeRecognizers.TEXT), results.map { it.text }, "results across the reattach")
        assertEquals(PluginStatus.Running, plugin.status.value)
    }

    @Test
    fun pauseClosesTheRegistrationAndResumeRegistersAgain() = runTest {
        val recognizers = FakeRecognizers()
        val plugin = OcrPlugin(OcrConfig(), recognizers)
        val session = ocrSession(plugin)
        startReady(session)

        plugin.pause()
        runCurrent()
        session.engine.fakeFrames.emit(countingFrame())
        assertEquals(0, session.frames.open, "registrations while paused")
        assertEquals(0, recognizers.seen.size, "frames read while paused")
        assertEquals(PluginStatus.Paused, plugin.status.value)

        plugin.resume()
        runCurrent()
        session.engine.fakeFrames.emit(countingFrame())
        assertEquals(1, session.frames.open, "registrations after resume")
        assertEquals(1, recognizers.seen.size, "frames read after resume")
        assertEquals(PluginStatus.Running, plugin.status.value)
    }

    @Test
    fun aSlowResultsCollectorNeverSuspendsRecognition() = runTest {
        val recognizers = FakeRecognizers()
        val plugin = OcrPlugin(OcrConfig(), recognizers)
        val session = ocrSession(plugin)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            plugin.results.collect { awaitCancellation() }
        }
        startReady(session)

        repeat(FRAMES_PAST_THE_BUFFER) {
            session.engine.fakeFrames.emit(countingFrame(timestampNanos = it * SECOND_NANOS))
        }

        assertEquals(FRAMES_PAST_THE_BUFFER, recognizers.seen.size, "frames read while the collector is stuck")
        val late = collectResults(plugin)
        session.engine.fakeFrames.emit(countingFrame(timestampNanos = FRAMES_PAST_THE_BUFFER * SECOND_NANOS))
        assertEquals(1, late.size, "a new collector still receives the next text")
    }

    @Test
    fun aRejectedRegistrationMarksFailed() = runTest {
        val rejection = CameraKError.Unsupported(CameraFeature.ANALYSIS_FORMAT)
        val plugin = OcrPlugin(OcrConfig(), FakeRecognizers())
        val session = ocrSession(plugin, rejection = rejection)

        startReady(session)

        val failed = assertIs<PluginStatus.Failed>(plugin.status.value)
        assertSame(rejection, failed.error)
        val events = session.events.filterIsInstance<CameraKEvent.PluginFailed>()
        assertEquals(listOf("OcrPlugin"), events.map { it.pluginId }, "PluginFailed events")
    }

    @Test
    fun aLiveRecognizerThatCannotStartMarksFailed() = runTest {
        val failure = IllegalStateException("no language data")
        val plugin = OcrPlugin(OcrConfig(), FakeRecognizers().apply { liveFailure = failure })
        val session = ocrSession(plugin)

        startReady(session)

        val failed = assertIs<PluginStatus.Failed>(plugin.status.value)
        // Coroutines on the JVM may rethrow a copy of the exception for its stack trace, so the
        // cause is checked by type and message.
        val cause = assertIs<IllegalStateException>(assertIs<CameraKError.Internal>(failed.error).cause)
        assertEquals(failure.message, cause.message)
        assertEquals(0, session.frames.open, "registrations for a recognizer that never started")
    }

    @Test
    fun aFailedFrameIsLoggedOnceAndTheNextFrameIsRead() = runTest {
        val failure = IllegalStateException("recognizer failed")
        var calls = 0
        val recognizers = FakeRecognizers().apply {
            liveAnswer = {
                if (++calls == 1) throw failure
                listOf(FoundBlock(FakeRecognizers.TEXT, null, null))
            }
        }
        val plugin = OcrPlugin(OcrConfig(), recognizers)
        val session = ocrSession(plugin)
        val results = collectResults(plugin)
        startReady(session)

        session.engine.fakeFrames.emit(countingFrame(timestampNanos = 0))
        session.engine.fakeFrames.emit(countingFrame(timestampNanos = SECOND_NANOS))

        assertEquals(listOf(failure), logged.map { it.second }, "error lines")
        assertEquals(listOf(FakeRecognizers.TEXT), results.map { it.text }, "text after the failed frame")
        assertEquals(PluginStatus.Running, plugin.status.value, "a failed frame changed the plugin's status")
    }

    private fun TestScope.collectResults(plugin: OcrPlugin): List<RecognizedText> {
        val results = mutableListOf<RecognizedText>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) {
            plugin.results.collect { results += it }
        }
        return results
    }

    private fun oneSecondOfFrames() = List(30) { countingFrame(timestampNanos = it * FRAME_NANOS) }

    private companion object {
        const val FRAME_NANOS = 33_333_333L

        // More than the results buffer holds, so a stuck collector would suspend an emitter that waited.
        const val FRAMES_PAST_THE_BUFFER = 40
    }
}

@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.controller.FakeFrameGrabber
import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The 1.x bridge over a real 1.x desktop controller and a fake camera: the session calls map to
 * engine states as the 1.x holder reported them, and the controller travels on the engine.
 */
class LegacyControllerEngineTest {
    private val environment = CameraKEnvironment(
        dispatchers = CameraKDispatchers(
            main = Dispatchers.Default,
            default = Dispatchers.Default,
            io = Dispatchers.IO,
        ),
    )
    private val desired = DesiredHardware(
        selection = CameraSelection.ByLens(CameraLens.BACK, CameraDeviceType.DEFAULT),
        flashMode = FlashMode.OFF,
        torchMode = TorchMode.OFF,
        zoomRatio = 1f,
        targetOrientation = null,
    )

    // Counts what reaches the 1.x session, around the real desktop session calls.
    private class CountingSession(private val delegate: LegacySession) : LegacySession {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val closes = AtomicInteger()

        override suspend fun start() {
            starts.incrementAndGet()
            delegate.start()
        }

        override suspend fun stop() {
            stops.incrementAndGet()
            delegate.stop()
        }

        override suspend fun close() {
            closes.incrementAndGet()
            delegate.close()
        }

        override suspend fun <T> onSessionThread(block: () -> T): T = delegate.onSessionThread(block)
    }

    // A session whose start, read-back of the 1.x getters, or close waits until the test opens its gate.
    private class GatedSession(holdStart: Boolean = false, holdReadBack: Boolean = false, holdClose: Boolean = false) :
        LegacySession {
        val startEntered = CompletableDeferred<Unit>()
        val readBackEntered = CompletableDeferred<Unit>()
        val closeEntered = CompletableDeferred<Unit>()
        val closeGate = gate(open = !holdClose)
        private val startGate = gate(open = !holdStart)
        private val readBackGate = gate(open = !holdReadBack)
        val stops = AtomicInteger()
        val closes = AtomicInteger()

        override suspend fun start() {
            startEntered.complete(Unit)
            startGate.await()
        }

        override suspend fun stop() {
            stops.incrementAndGet()
        }

        override suspend fun close() {
            closes.incrementAndGet()
            closeEntered.complete(Unit)
            closeGate.await()
        }

        override suspend fun <T> onSessionThread(block: () -> T): T {
            readBackEntered.complete(Unit)
            readBackGate.await()
            return block()
        }

        private fun gate(open: Boolean) = CompletableDeferred<Unit>().apply { if (open) complete(Unit) }
    }

    private fun controller(grabber: FakeFrameGrabber) =
        CameraController(imageFormat = ImageFormat.JPEG, directory = Directory.PICTURES, customGrabber = grabber)

    private fun engine(controller: CameraController, session: LegacySession) =
        LegacyControllerEngine(controller, InertPreview, session, environment)

    private object InertPreview : PlatformPreviewSource {
        override val frames: StateFlow<PreviewFrame?> = MutableStateFlow(null)
    }

    @Test
    fun exposesTheWrappedControllerThroughLegacyControllerAccess() = runBlocking {
        val controller = controller(FakeFrameGrabber())
        val sut: CameraEngine = engine(controller, DesktopLegacySession(controller, Dispatchers.IO))
        try {
            assertSame(controller, assertIs<LegacyControllerAccess>(sut).legacyController)
        } finally {
            withTimeout(TIMEOUT_MS) { sut.closeAndJoin() }
        }
    }

    @Test
    fun mapsSessionCallsToEngineStates() = runBlocking {
        val grabber = FakeFrameGrabber().apply { openGate() }
        val controller = controller(grabber)
        val session = CountingSession(DesktopLegacySession(controller, Dispatchers.IO))
        val sut = engine(controller, session)
        try {
            assertEquals(EngineState.Closed, sut.state.value, "at construction")

            assertEquals(CameraKResult.Success(Unit), sut.start(desired))
            assertEquals(EngineState.Open, sut.state.value, "after start")
            assertTrue(awaitCondition { grabber.startCount.get() == 1 }, "the grabber never started")

            assertEquals(CameraKResult.Success(Unit), sut.start(desired), "start while Open")
            assertEquals(1, session.starts.get(), "a start while Open reached the session")

            sut.stop()
            assertEquals(EngineState.Closed, sut.state.value, "after stop")
            assertTrue(grabber.awaitReleases(1, TIMEOUT_SECONDS), "stop did not release the grabber")

            assertEquals(CameraKResult.Success(Unit), sut.start(desired), "a second start")
            assertEquals(EngineState.Open, sut.state.value, "after the second start")
            assertTrue(awaitCondition { grabber.startCount.get() == 2 }, "the grabber did not start again")

            withTimeout(TIMEOUT_MS) { sut.closeAndJoin() }
            assertEquals(EngineState.Closed, sut.state.value, "after close")
            assertEquals(1, session.closes.get())
            assertEquals(grabber.startCount.get(), grabber.releaseCount.get(), "every grabber start was released")

            assertEquals(CameraKResult.Failure(CameraKError.Closed), sut.start(desired), "start after close")
            assertEquals(2, session.starts.get(), "a start after close reached the session")
            assertEquals(2, grabber.startCount.get(), "a start after close opened the camera")
        } finally {
            sut.close()
        }
    }

    @Test
    fun failingSessionStartIsFailedInternal() = runBlocking {
        val controller = controller(FakeFrameGrabber())
        val boom = IllegalStateException("the 1.x session failed to start")
        val throwing = object : LegacySession {
            override suspend fun start() = throw boom

            override suspend fun stop() = Unit

            override suspend fun close() = Unit

            override suspend fun <T> onSessionThread(block: () -> T): T = block()
        }
        val sut = engine(controller, throwing)
        try {
            val result = sut.start(desired)
            val atReturn = sut.state.value

            val error = assertIs<CameraKError.Internal>(assertIs<CameraKResult.Failure>(result).error)
            assertSame(boom, error.cause)
            val published = assertIs<CameraKError.Internal>(assertIs<EngineState.Failed>(atReturn).error)
            assertSame(boom, published.cause, "Failed(Internal) is published before start returns")
        } finally {
            withTimeout(TIMEOUT_MS) { sut.closeAndJoin() }
            controller.cleanup()
        }
    }

    @Test
    fun commandsReturnInvalidState() = runBlocking {
        val controller = controller(FakeFrameGrabber().apply { openGate() })
        val sut = engine(controller, DesktopLegacySession(controller, Dispatchers.IO))
        try {
            assertEquals(CameraKResult.Success(Unit), sut.start(desired))
            val video = VideoConfiguration(enableAudio = false)

            assertRefused(CameraOperation.CAPTURE, sut.capture())
            assertRefused(CameraOperation.ZOOM, sut.setZoom(1f))
            assertRefused(CameraOperation.FLASH, sut.setFlashMode(FlashMode.OFF))
            assertRefused(CameraOperation.TORCH, sut.setTorchMode(TorchMode.OFF))
            assertRefused(CameraOperation.LENS, sut.selectCamera(desired.selection))
            assertRefused(CameraOperation.ORIENTATION, sut.setTargetOrientation(null))
            assertRefused(CameraOperation.RECORD_PREPARE, sut.prepareRecording(video))
            assertRefused(CameraOperation.RECORD_START, sut.startRecording(video))
            assertRefused(CameraOperation.RECORD_STOP, sut.stopRecording())
            assertRefused(CameraOperation.RECORD_PAUSE, sut.pauseRecording())
            assertRefused(CameraOperation.RECORD_RESUME, sut.resumeRecording())
        } finally {
            withTimeout(TIMEOUT_MS) { sut.closeAndJoin() }
        }
    }

    @Test
    fun refusedCommandIsLoggedOnceAtWarningPerKind() = runBlocking {
        val logged = CopyOnWriteArrayList<Pair<CameraKLogger.Level, String>>()
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, _ -> if ("refuses" in message) logged += level to message }
        val controller = controller(FakeFrameGrabber())
        val first = engine(controller, GatedSession())
        val second = engine(controller, GatedSession())
        try {
            repeat(3) { first.setZoom(1f) }
            repeat(2) { first.capture() }
            first.setZoom(1f)
            assertEquals(2, logged.size, "refusals logged by one engine: $logged")
            assertTrue(logged.all { it.first == CameraKLogger.Level.WARN }, "refusals are warnings: $logged")
            assertContains(logged[0].second, "${CameraOperation.ZOOM}")
            assertContains(logged[1].second, "${CameraOperation.CAPTURE}")

            second.setZoom(1f)
            second.setZoom(1f)
            assertEquals(3, logged.size, "another engine logs its own first refusal: $logged")
        } finally {
            CameraKLogger.sink = null
            CameraKLogger.enabled = false
            withTimeout(TIMEOUT_MS) {
                first.closeAndJoin()
                second.closeAndJoin()
            }
            controller.cleanup()
        }
    }

    @Test
    fun startCancelledDuringTheSessionStartStopsTheSessionAndPublishesClosed() = runBlocking {
        val controller = controller(FakeFrameGrabber())
        val session = GatedSession(holdStart = true)
        val sut = engine(controller, session)
        try {
            val start = launch { sut.start(desired) }
            withTimeout(TIMEOUT_MS) { session.startEntered.await() }
            assertEquals(EngineState.Opening, sut.state.value, "inside the session start")

            start.cancelAndJoin()

            assertEquals(EngineState.Closed, sut.state.value, "after a start cancelled inside the session start")
            assertEquals(1, session.stops.get(), "the session the cancelled start may have started was not stopped")
        } finally {
            withTimeout(TIMEOUT_MS) { sut.closeAndJoin() }
            controller.cleanup()
        }
    }

    @Test
    fun startCancelledDuringTheReadBackStopsTheSessionAndPublishesClosed() = runBlocking {
        val controller = controller(FakeFrameGrabber())
        val session = GatedSession(holdReadBack = true)
        val sut = engine(controller, session)
        try {
            val start = launch { sut.start(desired) }
            withTimeout(TIMEOUT_MS) { session.readBackEntered.await() }
            assertEquals(EngineState.Opening, sut.state.value, "inside the read-back")

            start.cancelAndJoin()

            assertEquals(EngineState.Closed, sut.state.value, "after a start cancelled inside the read-back")
            assertEquals(1, session.stops.get(), "the session the cancelled start started was not stopped")
        } finally {
            withTimeout(TIMEOUT_MS) { sut.closeAndJoin() }
            controller.cleanup()
        }
    }

    @Test
    fun closeRunsUnderTheEnvironmentsParentJob() = runBlocking {
        val parent = Job()
        val controller = controller(FakeFrameGrabber())
        val session = GatedSession(holdClose = true)
        val sut = LegacyControllerEngine(controller, InertPreview, session, environment(parent))
        try {
            sut.close()
            withTimeout(TIMEOUT_MS) { session.closeEntered.await() }
            assertEquals(1, parent.children.count(), "the session close does not run under the environment's job")

            session.closeGate.complete(Unit)
            withTimeout(TIMEOUT_MS) {
                sut.closeAndJoin()
                parent.children.toList().joinAll()
            }
            assertEquals(1, session.closes.get())
        } finally {
            session.closeGate.complete(Unit)
            controller.cleanup()
            parent.cancel()
        }
    }

    @Test
    fun closeStillClosesTheSessionWhenTheParentJobIsCancelled() = runBlocking {
        val parent = Job()
        val controller = controller(FakeFrameGrabber())
        val session = GatedSession()
        val sut = LegacyControllerEngine(controller, InertPreview, session, environment(parent))
        try {
            parent.cancel()

            withTimeout(TIMEOUT_MS) { sut.closeAndJoin() }

            assertEquals(1, session.closes.get(), "the session was not closed under a cancelled parent job")
            assertEquals(EngineState.Closed, sut.state.value)
        } finally {
            controller.cleanup()
        }
    }

    private fun environment(parent: Job) = CameraKEnvironment(dispatchers = environment.dispatchers, parentJob = parent)

    private fun assertRefused(operation: CameraOperation, result: CameraKResult<*>) {
        val error = assertIs<CameraKError.InvalidState>(assertIs<CameraKResult.Failure>(result, "$operation").error)
        assertEquals(operation, error.operation)
    }

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MS)
        }
        return condition()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val TIMEOUT_SECONDS = 10L
        const val POLL_MS = 10L
    }
}

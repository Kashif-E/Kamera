@file:OptIn(CameraKEngineApi::class, ExperimentalCoroutinesApi::class)

package com.kashif.cameraK.state

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.controls.cycleFlashMode
import com.kashif.cameraK.controls.nextFlashMode
import com.kashif.cameraK.controls.toggleLens
import com.kashif.cameraK.controls.toggleTorch
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.CameraEngineFactory
import com.kashif.cameraK.engine.HardwareState
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.error.StorageFailure
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.FakeCapabilities
import com.kashif.cameraK.testing.FakeCaptures
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The holder's command surface over [FakeCameraEngine]: suspend commands that return the applied
 * value, the capture counter and the captures stream, latest-wins zoom, camera selection, the
 * common toggles and the bounded command drain in close.
 */
class CameraKStateHolderCommandTest {
    private val logged = mutableListOf<String>()

    @AfterTest
    fun restoreLogger() {
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    private fun captureLogs() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, _ -> logged += "$level $message" }
    }

    private fun TestScope.holderOver(
        engine: CameraEngine,
        plugins: List<CameraKPlugin> = emptyList(),
    ): CameraKStateHolder {
        val factory = CameraEngineFactory { _, _ -> engine }
        return CameraKStateHolder(CameraConfiguration(), factory, plugins, cameraKTestEnvironment())
    }

    private fun TestScope.readyHolder(
        engine: CameraEngine,
        plugins: List<CameraKPlugin> = emptyList(),
    ): CameraKStateHolder {
        val holder = holderOver(engine, plugins)
        holder.start()
        runCurrent()
        assertIs<CameraKState.Ready>(holder.cameraState.value, "the holder did not reach Ready")
        return holder
    }

    private fun TestScope.collectCaptures(holder: CameraKStateHolder): List<CapturedImage> {
        val received = mutableListOf<CapturedImage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { holder.captures.collect { received += it } }
        return received
    }

    private fun TestScope.collectEvents(holder: CameraKStateHolder): List<CameraKEvent> {
        val received = mutableListOf<CameraKEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { holder.events.collect { received += it } }
        return received
    }

    /** Each capture waits on its own gate; [release] lets the oldest waiting one return [result]. */
    private class GatedCaptures(fake: FakeCameraEngine) {
        private val gates = ArrayDeque<CompletableDeferred<CameraKResult<CapturedImage>>>()
        var started = 0
            private set

        init {
            fake.captureResult = {
                val gate = CompletableDeferred<CameraKResult<CapturedImage>>()
                gates.addLast(gate)
                started++
                gate.await()
            }
        }

        fun release(result: CameraKResult<CapturedImage> = CameraKResult.Success(FakeCaptures.image())) {
            gates.removeFirst().complete(result)
        }

        fun fail(error: Throwable) {
            gates.removeFirst().completeExceptionally(error)
        }
    }

    /** The fake with a hardware stream the test writes, standing in for what a platform reports. */
    private class ScriptedHardwareEngine(val fake: FakeCameraEngine, initial: HardwareState) : CameraEngine by fake {
        val reported = MutableStateFlow(initial)
        override val hardware: StateFlow<HardwareState> get() = reported
    }

    /** Records every engine call and the end of each capture, in one order, with the fake underneath. */
    private class OrderedEngine(val fake: FakeCameraEngine, val order: MutableList<String>) : CameraEngine by fake {
        override suspend fun capture(): CameraKResult<CapturedImage> = fake.capture().also { order += "captured" }

        override fun close() {
            order += "engine close"
            fake.close()
        }

        override suspend fun closeAndJoin() {
            order += "engine close"
            fake.closeAndJoin()
        }
    }

    private class DetachRecorder(private val order: MutableList<String>) : CameraKPlugin {
        override fun onAttach(stateHolder: CameraKStateHolder) = Unit

        override fun onDetach() {
            order += "plugin detach"
        }
    }

    private suspend fun assertEveryCommandFails(holder: CameraKStateHolder, expected: CameraKError) {
        val failure = CameraKResult.Failure(expected)
        assertEquals(failure, holder.capture(), "capture")
        assertEquals(failure, holder.setZoom(2f), "setZoom")
        assertEquals(failure, holder.setFlashMode(FlashMode.ON), "setFlashMode")
        assertEquals(failure, holder.setTorchMode(TorchMode.ON), "setTorchMode")
        assertEquals(failure, holder.selectCamera(CameraLens.FRONT), "selectCamera(lens)")
        assertEquals(failure, holder.selectCamera(FakeCapabilities.FRONT_ID), "selectCamera(id)")
        assertEquals(failure, holder.setTargetOrientation(DeviceOrientation.LANDSCAPE_LEFT), "setTargetOrientation")
        assertEquals(failure, holder.cycleFlashMode(), "cycleFlashMode")
        assertEquals(failure, holder.toggleTorch(), "toggleTorch")
        assertEquals(failure, holder.toggleLens(), "toggleLens")
    }

    private val lifecycleCalls = setOf("start", "stop", "close")

    @Test
    fun commandsBeforeReadyReturnNotReady() = runTest {
        val fake = FakeCameraEngine().apply { autoOpen = false }
        val holder = holderOver(fake)

        assertEveryCommandFails(holder, CameraKError.NotReady)

        holder.start()
        runCurrent()
        assertEquals(CameraKState.Starting, holder.cameraState.value)
        assertEveryCommandFails(holder, CameraKError.NotReady)
        assertEquals(listOf("start"), fake.calls, "a command reached the engine before Ready")
    }

    @Test
    fun commandsAfterCloseReturnClosed() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)

        holder.closeAndJoin()

        assertEveryCommandFails(holder, CameraKError.Closed)
        assertEquals(emptyList(), fake.calls.filterNot { it in lifecycleCalls }, "a command reached the engine")
    }

    @Test
    fun setZoomReturnsEngineClampedValue() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)

        val result = holder.setZoom(25f)

        assertEquals(CameraKResult.Success(ZoomState(10f, 1f, 10f)), result, "the engine's clamped value")
        assertEquals(10f, fake.appliedZoomRatio, "the ratio the platform received")
        assertEquals(1, fake.setZoomCalls)
    }

    @Test
    fun tenRapidSetZoomCallsMakeAtMostTwoEngineCallsAndAllGetTheFinalValue() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)

        val calls = (1..10).map { step -> async { holder.setZoom(step.toFloat()) } }
        val results = calls.awaitAll()

        assertTrue(fake.setZoomCalls <= 2, "engine setZoom calls: ${fake.setZoomCalls}")
        val final = CameraKResult.Success(ZoomState(10f, 1f, 10f))
        results.forEachIndexed { index, result -> assertEquals(final, result, "call ${index + 1}") }
        assertEquals(10f, fake.appliedZoomRatio)
    }

    @Test
    fun supersededZoomCallNeitherFailsNorLogs() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)
        captureLogs()

        val superseded = async { holder.setZoom(2f) }
        val replacing = async { holder.setZoom(3f) }
        runCurrent()

        val final = CameraKResult.Success(ZoomState(3f, 1f, 10f))
        assertEquals(final, superseded.await(), "the superseded call")
        assertEquals(final, replacing.await(), "the replacing call")
        assertEquals(emptyList(), logged, "a superseded call logged")
    }

    @Test
    fun uiStateFollowsEngineHardware() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)
        assertEquals(FakeCapabilities.BACK_ID, holder.uiState.value.cameraId, "the camera the first bind reported")

        assertEquals(CameraKResult.Success(FlashMode.AUTO), holder.setFlashMode(FlashMode.AUTO))
        runCurrent()
        assertEquals(FlashMode.OFF, holder.uiState.value.flashMode, "uiState took the request before the platform")

        fake.reportFlash(FlashMode.AUTO)
        fake.reportTorch(TorchMode.ON)
        fake.reportCamera(FakeCapabilities.FRONT_ID)
        runCurrent()

        val state = holder.uiState.value
        assertEquals(FlashMode.AUTO, state.flashMode)
        assertEquals(TorchMode.ON, state.torchMode)
        assertEquals(CameraLens.FRONT, state.cameraLens)
        assertEquals(CameraDeviceType.WIDE_ANGLE, state.cameraDeviceType)
        assertEquals(FakeCapabilities.FRONT_ID, state.cameraId)
    }

    @Test
    fun twoConcurrentCapturesKeepIsCapturingUntilBothFinish() = runTest {
        val fake = FakeCameraEngine()
        val gates = GatedCaptures(fake)
        val holder = readyHolder(fake)

        val first = async { holder.capture() }
        val second = async { holder.capture() }
        runCurrent()
        assertEquals(2, holder.uiState.value.pendingCaptures)
        assertTrue(holder.uiState.value.isCapturing)

        gates.release()
        runCurrent()
        assertEquals(1, holder.uiState.value.pendingCaptures)
        assertTrue(holder.uiState.value.isCapturing, "isCapturing went false with a capture still running")

        gates.release()
        runCurrent()
        assertEquals(0, holder.uiState.value.pendingCaptures)
        assertEquals(false, holder.uiState.value.isCapturing)
        assertIs<CameraKResult.Success<CapturedImage>>(first.await())
        assertIs<CameraKResult.Success<CapturedImage>>(second.await())
    }

    @Test
    fun captureFailureIsFailureAndEmitsNoEvent() = runTest {
        val fake = FakeCameraEngine()
        val error = CameraKError.Storage(StorageFailure.FULL)
        fake.captureResult = { CameraKResult.Failure(error) }
        val holder = readyHolder(fake)
        val events = collectEvents(holder)
        val captures = collectCaptures(holder)

        val result = holder.capture()
        runCurrent()

        assertSame(error, assertIs<CameraKResult.Failure>(result).error)
        assertEquals(emptyList(), events, "a failed capture emitted an event")
        assertEquals(emptyList(), captures, "a failed capture reached captures")
        assertEquals(0, holder.uiState.value.pendingCaptures)
    }

    @Test
    fun cancelledCallerCaptureCompletesOnceAndReachesCaptures() = runTest {
        val fake = FakeCameraEngine()
        val gates = GatedCaptures(fake)
        val holder = readyHolder(fake)
        val captures = collectCaptures(holder)

        val caller = launch { holder.capture() }
        runCurrent()
        caller.cancel()
        runCurrent()
        assertEquals(1, holder.uiState.value.pendingCaptures, "the capture stopped with its caller")

        val image = FakeCaptures.image(path = "/fake/IMG_0042.jpg")
        gates.release(CameraKResult.Success(image))
        runCurrent()

        assertEquals(listOf(image), captures, "what reached captures")
        assertEquals(1, gates.started, "engine captures")
        assertEquals(0, holder.uiState.value.pendingCaptures)
    }

    @Test
    fun fourthInFlightCaptureIsBusy() = runTest {
        val fake = FakeCameraEngine()
        val gates = GatedCaptures(fake)
        val holder = readyHolder(fake)

        val running = List(3) { async { holder.capture() } }
        runCurrent()
        assertEquals(3, holder.uiState.value.pendingCaptures)

        val fourth = holder.capture()

        val busy = assertIs<CameraKError.Busy>(assertIs<CameraKResult.Failure>(fourth).error)
        assertEquals(CameraOperation.CAPTURE, busy.operation)
        assertEquals(3, gates.started, "the fourth capture reached the engine")
        assertEquals(3, holder.uiState.value.pendingCaptures)
        repeat(3) { gates.release() }
        running.awaitAll().forEach { assertIs<CameraKResult.Success<CapturedImage>>(it) }
        assertEquals(0, holder.uiState.value.pendingCaptures)
    }

    @Test
    fun counterDecrementsOnceWhenEngineFailsAfterCallerCancel() = runTest {
        val fake = FakeCameraEngine()
        val gates = GatedCaptures(fake)
        val holder = readyHolder(fake)
        val counts = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            holder.uiState.collect { counts += it.pendingCaptures }
        }

        val caller = launch { holder.capture() }
        runCurrent()
        caller.cancel()
        runCurrent()
        gates.fail(IllegalStateException("the platform capture failed"))
        runCurrent()

        assertEquals(0, holder.uiState.value.pendingCaptures)
        assertTrue(counts.all { it >= 0 }, "pendingCaptures went negative: $counts")
        assertEquals(1, counts.zipWithNext().count { (before, after) -> after < before }, "decrements: $counts")

        val next = async { holder.capture() }
        runCurrent()
        assertEquals(1, holder.uiState.value.pendingCaptures, "the next capture counted from a wrong base")
        gates.release()
        assertIs<CameraKResult.Success<CapturedImage>>(next.await())
        assertEquals(0, holder.uiState.value.pendingCaptures)
    }

    @Test
    fun capturesDeliversEachCaptureOnceToEverySubscriber() = runTest {
        val fake = FakeCameraEngine()
        var taken = 0
        fake.captureResult = {
            taken++
            CameraKResult.Success(FakeCaptures.image(path = "/fake/IMG_000$taken.jpg"))
        }
        val holder = readyHolder(fake)
        val first = collectCaptures(holder)
        val second = collectCaptures(holder)

        val one = assertIs<CameraKResult.Success<CapturedImage>>(holder.capture()).value
        val two = assertIs<CameraKResult.Success<CapturedImage>>(holder.capture()).value
        runCurrent()

        assertEquals(listOf(one, two), first, "the first subscriber")
        assertEquals(listOf(one, two), second, "the second subscriber")
    }

    @Test
    fun cycleFlashModeFollowsDocumentedOrderOverSupportedModes() = runTest {
        val all = setOf(FlashMode.OFF, FlashMode.AUTO, FlashMode.ON)
        assertEquals(FlashMode.AUTO, nextFlashMode(FlashMode.OFF, all))
        assertEquals(FlashMode.ON, nextFlashMode(FlashMode.AUTO, all))
        assertEquals(FlashMode.OFF, nextFlashMode(FlashMode.ON, all))
        val noAuto = setOf(FlashMode.OFF, FlashMode.ON)
        assertEquals(FlashMode.ON, nextFlashMode(FlashMode.OFF, noAuto), "AUTO unsupported")
        assertEquals(FlashMode.OFF, nextFlashMode(FlashMode.ON, noAuto))
        assertEquals(FlashMode.ON, nextFlashMode(FlashMode.AUTO, noAuto), "from a mode the camera does not offer")
        assertEquals(FlashMode.OFF, nextFlashMode(FlashMode.OFF, setOf(FlashMode.OFF)), "OFF only")
        assertEquals(FlashMode.OFF, nextFlashMode(FlashMode.ON, emptySet()), "nothing offered")

        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)
        val seen = mutableListOf<FlashMode>()
        repeat(4) {
            val mode = assertIs<CameraKResult.Success<FlashMode>>(holder.cycleFlashMode()).value
            seen += mode
            fake.reportFlash(mode)
            runCurrent()
        }
        assertEquals(listOf(FlashMode.AUTO, FlashMode.ON, FlashMode.OFF, FlashMode.AUTO), seen)
    }

    @Test
    fun selectCameraByLensAndTypeMakesOneEngineCall() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)

        val selected = holder.selectCamera(CameraLens.FRONT, CameraDeviceType.WIDE_ANGLE)

        assertEquals(FakeCapabilities.FRONT_ID, assertIs<CameraKResult.Success<LensInfo>>(selected).value.id)
        assertEquals(1, fake.calls.count { it == "selectCamera" }, "engine calls: ${fake.calls}")
        assertEquals(FakeCapabilities.FRONT_ID, fake.appliedCameraId)

        val byId = holder.selectCamera(FakeCapabilities.BACK_ID)
        assertEquals(FakeCapabilities.BACK_ID, assertIs<CameraKResult.Success<LensInfo>>(byId).value.id)
        assertEquals(2, fake.calls.count { it == "selectCamera" }, "engine calls: ${fake.calls}")
    }

    @Test
    fun selectCameraUnknownIdIsNoCameraAndKeepsCamera() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)

        val result = holder.selectCamera("no-such-camera")
        runCurrent()

        assertIs<CameraKError.NoCamera>(assertIs<CameraKResult.Failure>(result).error)
        assertEquals(FakeCapabilities.BACK_ID, fake.appliedCameraId)
        assertEquals(FakeCapabilities.BACK_ID, holder.uiState.value.cameraId)

        holder.stop()
        runCurrent()
        holder.start()
        runCurrent()
        assertEquals(FakeCapabilities.BACK_ID, fake.appliedCameraId, "the next start opened another camera")
    }

    @Test
    fun cycleFlashModeWithoutACameraIdStaysOff() = runTest {
        val fake = FakeCameraEngine(initialFlash = FlashMode.ON)
        val engine = ScriptedHardwareEngine(fake, HardwareState(FlashMode.ON, TorchMode.OFF, null, null))
        val holder = readyHolder(engine)
        assertEquals(null, holder.uiState.value.cameraId)

        assertEquals(CameraKResult.Success(FlashMode.OFF), holder.cycleFlashMode())
        assertEquals(FlashMode.OFF, fake.appliedFlashMode)
        engine.reported.value = HardwareState(FlashMode.OFF, TorchMode.OFF, null, null)
        runCurrent()
        assertEquals(CameraKResult.Success(FlashMode.OFF), holder.cycleFlashMode(), "a second cycle")
    }

    @Test
    fun capabilitiesAreTheEngines() = runTest {
        val offered = FakeCapabilities.backAndFront()
        val noAuto = CameraCapabilities(
            allLenses = offered.allLenses,
            flashModes = offered.allLenses.associate { it.id to setOf(FlashMode.OFF, FlashMode.ON) },
            torchModes = offered.allLenses.associate { it.id to offered.supportedTorchModes(it.id) },
            supportsPauseResume = offered.supportsPauseResume,
            supportedVideoQualities = offered.supportedVideoQualities,
            supportsVideoBitrate = offered.supportsVideoBitrate,
            supportedAspectRatios = offered.supportedAspectRatios,
            supportedImageFormats = offered.supportedImageFormats,
            supportedAnalysisFormats = offered.supportedAnalysisFormats,
            supportsSquareCapture = offered.supportsSquareCapture,
        )
        val fake = FakeCameraEngine(capabilities = noAuto)
        val holder = holderOver(fake)
        assertSame(fake.capabilities.value, holder.capabilities.value, "before the first start")

        holder.start()
        runCurrent()
        assertIs<CameraKState.Ready>(holder.cameraState.value)
        assertSame(fake.capabilities.value, holder.capabilities.value, "at Ready")
        assertEquals(CameraKResult.Success(FlashMode.ON), holder.cycleFlashMode(), "AUTO is not offered")
    }

    @Test
    fun closeDetachesPluginsThenDrainsCommandsThenClosesEngine() = runTest {
        val order = mutableListOf<String>()
        val fake = FakeCameraEngine()
        val engine = OrderedEngine(fake, order)
        val gates = GatedCaptures(fake)
        val holder = readyHolder(engine, listOf(DetachRecorder(order)))
        val capture = async { holder.capture() }
        runCurrent()

        holder.close()
        runCurrent()
        advanceTimeBy(COMMAND_DRAIN_TIMEOUT / 2)
        assertEquals(listOf("plugin detach"), order, "close went past the drain with a command running")

        gates.release()
        runCurrent()

        assertEquals(listOf("plugin detach", "captured", "engine close"), order)
        assertIs<CameraKResult.Success<CapturedImage>>(capture.await())
        assertEquals(1, fake.closeCalls)
    }

    @Test
    fun closeWithACaptureThatNeverCompletesStillClosesTheEngine() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)
        fake.holdNextCapture()
        val capture = async { holder.capture() }
        runCurrent()
        assertEquals(1, holder.uiState.value.pendingCaptures)

        holder.close()
        advanceTimeBy(COMMAND_DRAIN_TIMEOUT - 1.milliseconds)
        assertEquals(0, fake.closeCalls, "the engine closed before the drain timed out")
        advanceTimeBy(1.milliseconds)
        runCurrent()

        assertEquals(1, fake.closeCalls)
        assertEquals(CameraKResult.Failure(CameraKError.Closed), capture.await())
        assertEquals(0, holder.uiState.value.pendingCaptures)
    }

    @Test
    fun captureFinishingInsideTheDrainKeepsItsResult() = runTest {
        val fake = FakeCameraEngine()
        val image = FakeCaptures.image(path = "/fake/IMG_0007.jpg")
        fake.captureResult = { CameraKResult.Success(image) }
        val holder = readyHolder(fake)
        val captures = collectCaptures(holder)
        fake.holdNextCapture()
        val capture = async { holder.capture() }
        runCurrent()

        holder.close()
        runCurrent()
        fake.releaseCapture()
        runCurrent()

        assertEquals(CameraKResult.Success(image), capture.await())
        assertEquals(listOf(image), captures)
        assertEquals(1, fake.closeCalls)
        assertEquals(0, holder.uiState.value.pendingCaptures)
    }
}

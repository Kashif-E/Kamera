@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine.contract

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineRecordingEvent
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The contract kit's cases, one function each, so [CameraEngineContractTest] runs them for a real
 * driver and the kit's own tests can run one over a fake they configure.
 *
 * The hardware cases adapt to the engine: they take their values from `capabilities.value` for the
 * camera the platform bound, skip a step the engine cannot run, record it in [skips], and fail
 * with "nothing to verify on this engine" when every step was skipped.
 */
internal class ContractCases(
    private val driver: EngineContractDriver,
    private val requires: Set<KClass<out EngineContractDriver>>,
    private val skips: ContractSkips,
) {
    private val desired = DesiredHardware(
        selection = CameraSelection.ByLens(CameraLens.BACK, CameraDeviceType.DEFAULT),
        flashMode = FlashMode.OFF,
        torchMode = TorchMode.OFF,
        zoomRatio = 1f,
        targetOrientation = null,
    )

    // Desktop rejects enableAudio = true; no case here is about audio.
    private val video = VideoConfiguration(enableAudio = false)
    private val reason = InterruptionReason.IN_USE_BY_ANOTHER_CLIENT
    private val success = CameraKResult.Success(Unit)
    private val closed = CameraKResult.Failure(CameraKError.Closed)

    fun driverImplementsEveryRequiredSubInterface() {
        val missing = requires.filterNot { it.isInstance(driver) }
        assertTrue(
            missing.isEmpty(),
            "the driver lacks ${missing.map { it.simpleName }}, which this engine must implement from its slice on",
        )
    }

    suspend fun startReturnsOnlyAfterPlatformOpen(scope: TestScope) = withEngine { engine ->
        driver.holdOpen()
        val start = scope.async { engine.start(desired) }
        driver.settle()
        assertFalse(start.isCompleted, "start returned before the platform opened the camera")
        assertNotEquals<EngineState>(EngineState.Open, engine.state.value, "Open before the platform confirmed it")

        driver.deliverOpen()
        driver.settle()
        assertTrue(start.isCompleted, "start did not return after the platform opened the camera")
        assertEquals(success, start.await())
        assertEquals(EngineState.Open, engine.state.value)
    }

    suspend fun stateIsOpenOrInterruptedWhenStartReturnsSuccess() = withEngine { engine ->
        val result = engine.start(desired)
        val atReturn = engine.state.value

        assertEquals(success, result)
        assertTrue(atReturn == EngineState.Open || atReturn is EngineState.Interrupted, "state at return: $atReturn")
    }

    suspend fun failedStartPublishesFailedBeforeReturningFailure(scope: TestScope) = withEngine { engine ->
        driver.holdOpen()
        val start = scope.async { engine.start(desired).let { it to engine.state.value } }
        driver.settle()
        driver.failOpen()
        driver.settle()

        val (result, atReturn) = start.await()
        val error = assertIs<CameraKResult.Failure>(result, "start after a failed open").error
        assertTrue(error.isRetryable, "a failed open maps to a retryable error, not $error")
        val failed = assertIs<EngineState.Failed>(atReturn, "state when start returned")
        assertEquals(error::class, failed.error::class, "Failed carries the error start returned")
    }

    suspend fun unsupportedConfigurationStartPublishesFailedAndReturnsUnsupported() {
        val unsupported = driver as? UnsupportedConfigurationDriver
            ?: return skipCase(
                "unsupportedConfigurationStartPublishesFailedAndReturnsUnsupported",
                "UnsupportedConfigurationDriver",
            )
        val case = unsupported.unsupportedConfiguration()
        withEngine(case.config) { engine ->
            val result = engine.start(desired)
            val atReturn = engine.state.value

            val error = assertIs<CameraKError.Unsupported>(assertIs<CameraKResult.Failure>(result).error)
            assertEquals(case.feature, error.feature)
            val published = assertIs<CameraKError.Unsupported>(assertIs<EngineState.Failed>(atReturn).error)
            assertEquals(case.feature, published.feature)
        }
    }

    suspend fun interruptBeforeOpenThenEndInterruptionReachesOpenWithOneStartCall(scope: TestScope) =
        withEngine { engine ->
            driver.holdOpen()
            val start = scope.async { engine.start(desired) }
            driver.settle()
            driver.interrupt(reason)
            driver.settle()

            assertEquals(success, start.await(), "a start the platform holds interrupted succeeds")
            assertEquals(EngineState.Interrupted(reason), engine.state.value)

            driver.endInterruption()
            driver.settle()
            assertEquals(
                EngineState.Open,
                engine.state.value,
                "the platform resumed the session without a second start",
            )
        }

    suspend fun endInterruptionAfterStopLeavesEngineClosed() = withEngine { engine ->
        open(engine)
        driver.interrupt(reason)
        driver.settle()
        assertIs<EngineState.Interrupted>(engine.state.value)

        engine.stop()
        driver.settle()
        driver.endInterruption()
        driver.settle()

        assertEquals(EngineState.Closed, engine.state.value)
    }

    suspend fun stopDuringHeldStartLeavesCameraClosedWhenOpenArrivesLate(scope: TestScope) = withEngine { engine ->
        driver.holdOpen()
        val start = scope.async { engine.start(desired) }
        driver.settle()

        engine.stop()
        driver.settle()
        assertEquals(EngineState.Closed, engine.state.value)
        assertTrue(start.isCompleted, "a start the stop cut short never returned")
        assertNotEquals<CameraKResult<Unit>>(success, start.await())

        driver.deliverOpen()
        driver.settle()
        assertEquals(EngineState.Closed, engine.state.value, "the late open reopened a stopped camera")
    }

    suspend fun cancelledStartLeavesCameraClosed(scope: TestScope) = withEngine { engine ->
        driver.holdOpen()
        val start = scope.launch { engine.start(desired) }
        driver.settle()

        start.cancel()
        driver.settle()
        assertEquals(EngineState.Closed, engine.state.value)

        driver.deliverOpen()
        driver.settle()
        assertEquals(EngineState.Closed, engine.state.value, "the late open reopened a cancelled start")
    }

    suspend fun callbacksAfterCloseDoNothing() = withEngine { engine ->
        open(engine)
        val lens = currentLens(engine)
        val torch = torchTarget(engine, lens)
        val zoom = zoomTarget(engine, lens)
        val hardware = engine.hardware.value
        val zoomState = engine.zoom.value

        engine.close()
        driver.settle()
        driver.deliverOpen()
        driver.interrupt(reason)
        driver.endInterruption()
        driver.fail()
        driver.changeHardware(HardwareChange(torchMode = torch, zoomRatio = zoom))
        driver.settle()

        assertEquals(EngineState.Closed, engine.state.value)
        assertEquals(hardware, engine.hardware.value)
        assertEquals(zoomState, engine.zoom.value)
    }

    suspend fun callsAfterCloseReturnClosed() = withEngine { engine ->
        open(engine)
        val zoom = engine.zoom.value.ratio
        engine.close()
        driver.settle()

        assertEquals(closed, engine.start(desired), "start")
        assertEquals(closed, engine.capture(), "capture")
        assertEquals(closed, engine.setZoom(zoom), "setZoom")
        assertEquals(closed, engine.setFlashMode(FlashMode.OFF), "setFlashMode")
        assertEquals(closed, engine.setTorchMode(TorchMode.OFF), "setTorchMode")
        assertEquals(closed, engine.selectCamera(desired.selection), "selectCamera")
        assertEquals(closed, engine.setTargetOrientation(null), "setTargetOrientation")
        assertEquals(closed, engine.prepareRecording(video), "prepareRecording")
        assertEquals(closed, engine.startRecording(video), "startRecording")
        assertEquals(closed, engine.stopRecording(), "stopRecording")
        assertEquals(closed, engine.pauseRecording(), "pauseRecording")
        assertEquals(closed, engine.resumeRecording(), "resumeRecording")
        engine.stop()
        assertEquals(EngineState.Closed, engine.state.value)
    }

    suspend fun closeIsIdempotentAndReleasesOnce() = withEngine { engine ->
        open(engine)

        engine.close()
        engine.close()
        driver.settle()
        assertEquals(1, driver.releaseCount(), "native releases after two closes")

        engine.closeAndJoin()
        assertEquals(1, driver.releaseCount(), "native releases after closeAndJoin")
    }

    suspend fun hardwareChangesOnlyFromPlatformObserver() {
        val case = "hardwareChangesOnlyFromPlatformObserver"
        withEngine { engine ->
            open(engine)
            val bound = driver.boundSettings()
            val lens = currentLens(engine)
            val steps = Steps(case)

            steps.step("reported camera", skipIf = noLens(lens)) {
                assertEquals(
                    bound.cameraId,
                    engine.hardware.value.camera?.id,
                    "the reported camera is not the bound one",
                )
            }
            val torch = torchTarget(engine, lens)
            steps.step("torch", skipIf = skipReason(lens, torch) { onlyTorch(engine, it) }) {
                driver.changeHardware(HardwareChange(torchMode = torch))
                driver.settle()
                assertEquals(torch, engine.hardware.value.torchMode, "the platform's torch report")
                assertEquals(bound, driver.boundSettings(), "a platform report is not a command")
            }
            val zoom = zoomTarget(engine, lens)
            steps.step("zoom", skipIf = skipReason(lens, zoom) { fixedZoom(it) }) {
                driver.changeHardware(HardwareChange(zoomRatio = zoom))
                driver.settle()
                assertEquals(zoom, engine.zoom.value.ratio, "the platform's zoom report")
                assertEquals(bound, driver.boundSettings(), "a platform report is not a command")
            }
            steps.requireSomethingRan()
        }
    }

    suspend fun desiredHardwareReappliedAfterRestartAndCameraSwitch() {
        val case = "desiredHardwareReappliedAfterRestartAndCameraSwitch"
        withEngine { engine ->
            open(engine)
            val lens = currentLens(engine)
            val capabilities = engine.capabilities.value
            val flash = lens?.let { flashTarget(engine, it) }
            val torch = torchTarget(engine, lens)
            val zoom = zoomTarget(engine, lens)
            val restarted = DesiredHardware(
                selection = desired.selection,
                flashMode = flash ?: desired.flashMode,
                torchMode = torch ?: desired.torchMode,
                zoomRatio = zoom ?: desired.zoomRatio,
                targetOrientation = null,
            )
            val steps = Steps(case)

            steps.step("camera after restart", skipIf = noLens(lens)) {
                engine.stop()
                driver.settle()
                assertEquals(success, engine.start(restarted), "start after stop")
                driver.settle()
                assertEquals(lens?.id, driver.boundSettings().cameraId)
            }
            steps.step("flash after restart", skipIf = skipReason(lens, flash) { onlyFlash(engine, it) }) {
                assertEquals(flash, driver.boundSettings().flashMode)
            }
            steps.step("torch after restart", skipIf = skipReason(lens, torch) { onlyTorch(engine, it) }) {
                assertEquals(torch, driver.boundSettings().torchMode)
            }
            steps.step("zoom after restart", skipIf = skipReason(lens, zoom) { fixedZoom(it) }) {
                assertEquals(zoom, driver.boundSettings().zoomRatio)
            }
            val other = lens?.let { current -> capabilities.allLenses.firstOrNull { it.id != current.id } }
            steps.step("camera switch", skipIf = if (other == null) "the engine has fewer than two cameras" else null) {
                val target = other!!
                val selected = engine.selectCamera(CameraSelection.ById(target.id))
                assertEquals(target.id, assertIs<CameraKResult.Success<LensInfo>>(selected).value.id)
                driver.settle()
                val bound = driver.boundSettings()
                assertEquals(target.id, bound.cameraId)
                if (flash != null && flash in capabilities.supportedFlashModes(target.id)) {
                    assertEquals(flash, bound.flashMode, "flash after the switch")
                }
                if (torch != null && torch in capabilities.supportedTorchModes(target.id)) {
                    assertEquals(torch, bound.torchMode, "torch after the switch")
                }
                if (zoom != null && zoom in target.minZoom..target.maxZoom) {
                    assertEquals(zoom, bound.zoomRatio, "zoom after the switch")
                }
            }
            steps.requireSomethingRan()
        }
    }

    suspend fun noCallbackOfAFailedGenerationChangesState() = withEngine { engine ->
        open(engine)
        driver.fail()
        driver.settle()
        val failed = assertIs<EngineState.Failed>(engine.state.value)

        driver.deliverOpen()
        driver.interrupt(reason)
        driver.endInterruption()
        driver.settle()

        assertEquals(failed, engine.state.value, "a callback of the failed generation changed the state")
    }

    suspend fun stopAfterFailedPublishesClosedAndStartBeginsANewGeneration() = withEngine { engine ->
        open(engine)
        driver.fail()
        driver.settle()
        assertIs<EngineState.Failed>(engine.state.value)

        engine.stop()
        driver.settle()
        assertEquals(EngineState.Closed, engine.state.value)

        assertEquals(success, engine.start(desired), "start after a failed generation")
        driver.settle()
        assertEquals(EngineState.Open, engine.state.value)
    }

    suspend fun closeDuringRecordingFinalizesWithShutdownSuccess(scope: TestScope) {
        val recording = driver as? RecordingDriver
            ?: return skipCase("closeDuringRecordingFinalizesWithShutdownSuccess", "RecordingDriver")
        withEngine { engine ->
            val events = recordInto(scope, engine)
            val path = startRecording(scope, engine, recording)

            engine.close()
            driver.settle()
            assertEquals(0, driver.releaseCount(), "close released the camera before the recording was finalized")
            recording.deliverRecordingFinalized()
            driver.settle()

            assertFinalizedWithShutdown(events, path)
            engine.closeAndJoin()
            assertEquals(1, driver.releaseCount(), "native releases after the recording was finalized")
        }
    }

    suspend fun stopDuringRecordingFinalizesWithShutdownSuccess(scope: TestScope) {
        val recording = driver as? RecordingDriver
            ?: return skipCase("stopDuringRecordingFinalizesWithShutdownSuccess", "RecordingDriver")
        withEngine { engine ->
            val events = recordInto(scope, engine)
            val path = startRecording(scope, engine, recording)

            val stop = scope.async { engine.stop() }
            driver.settle()
            assertFalse(stop.isCompleted, "stop returned before the recording was finalized")
            recording.deliverRecordingFinalized()
            driver.settle()
            stop.await()

            assertFinalizedWithShutdown(events, path)
            assertEquals(EngineState.Closed, engine.state.value)
        }
    }

    suspend fun closeCompletesAPendingCaptureWithClosed(scope: TestScope) {
        val capture = driver as? CaptureDriver
            ?: return skipCase("closeCompletesAPendingCaptureWithClosed", "CaptureDriver")
        withEngine { engine ->
            open(engine)
            capture.holdNextCapture()
            val pending = scope.async { engine.capture() }
            driver.settle()
            assertFalse(pending.isCompleted, "the held capture returned before its platform callback")

            engine.close()
            driver.settle()
            assertTrue(pending.isCompleted, "close left a pending capture waiting")
            assertEquals(closed, pending.await())

            capture.releaseCapture()
            driver.settle()
        }
    }

    suspend fun throwingPlatformCallbackDoesNotEscape(scope: TestScope) {
        val throwing = driver as? ThrowingCallbackDriver
            ?: return skipCase("throwingPlatformCallbackDoesNotEscape", "ThrowingCallbackDriver")
        withEngine { engine ->
            driver.holdOpen()
            val start = scope.async { engine.start(desired) }
            driver.settle()

            throwing.throwFromNextCallback()
            driver.deliverOpen()
            driver.settle()

            driver.deliverOpen()
            driver.settle()
            assertEquals(success, start.await(), "the engine survived a throwing callback and opened on the next one")
            assertEquals(EngineState.Open, engine.state.value)
        }
    }

    private suspend fun withEngine(
        config: CameraConfiguration = CameraConfiguration(),
        body: suspend (CameraEngine) -> Unit,
    ) {
        val engine = driver.createEngine(config)
        try {
            body(engine)
        } finally {
            engine.close()
        }
    }

    private suspend fun open(engine: CameraEngine) {
        assertEquals(success, engine.start(desired), "start")
        driver.settle()
        assertEquals(EngineState.Open, engine.state.value)
    }

    private suspend fun startRecording(scope: TestScope, engine: CameraEngine, recording: RecordingDriver): String {
        open(engine)
        val start = scope.async { engine.startRecording(video) }
        driver.settle()
        recording.deliverRecordingStarted()
        driver.settle()
        return assertIs<CameraKResult.Success<String>>(start.await(), "startRecording").value
    }

    private suspend fun recordInto(scope: TestScope, engine: CameraEngine): List<EngineRecordingEvent> {
        val events = mutableListOf<EngineRecordingEvent>()
        scope.backgroundScope.launch { engine.recordingEvents.collect { events += it } }
        driver.settle()
        return events
    }

    private fun assertFinalizedWithShutdown(events: List<EngineRecordingEvent>, path: String) {
        val finalized = events.filterIsInstance<EngineRecordingEvent.Finalized>()
        assertEquals(1, finalized.size, "Finalized events: $events")
        assertEquals(RecordingEndReason.SHUTDOWN, finalized.single().reason)
        val video = assertIs<CameraKResult.Success<RecordedVideo>>(finalized.single().result).value
        assertEquals(path, video.path, "the finalized file is the one that started")
    }

    // The camera the platform bound, as the engine's capabilities describe it.
    private fun currentLens(engine: CameraEngine): LensInfo? {
        val id = driver.boundSettings().cameraId ?: return null
        return engine.capabilities.value.allLenses.firstOrNull { it.id == id }
    }

    private fun flashTarget(engine: CameraEngine, lens: LensInfo): FlashMode? {
        val supported = engine.capabilities.value.supportedFlashModes(lens.id)
        return listOf(FlashMode.ON, FlashMode.AUTO, FlashMode.OFF).firstOrNull {
            it != desired.flashMode &&
                it in supported
        }
    }

    private fun torchTarget(engine: CameraEngine, lens: LensInfo?): TorchMode? {
        lens ?: return null
        val current = engine.hardware.value.torchMode
        val supported = engine.capabilities.value.supportedTorchModes(lens.id)
        return listOf(TorchMode.ON, TorchMode.AUTO, TorchMode.OFF).firstOrNull { it != current && it in supported }
    }

    private fun zoomTarget(engine: CameraEngine, lens: LensInfo?): Float? {
        lens ?: return null
        if (lens.maxZoom <= lens.minZoom) return null
        val current = engine.zoom.value.ratio
        val middle = (lens.minZoom + lens.maxZoom) / 2
        return if (middle != current) middle else lens.minZoom + (lens.maxZoom - lens.minZoom) / 4
    }

    private fun noLens(lens: LensInfo?): String? = if (lens == null) NO_LENS else null

    // Why a step that needs [target] on [lens] cannot run, or null when it can.
    private fun skipReason(lens: LensInfo?, target: Any?, unsupported: (LensInfo) -> String): String? = when {
        lens == null -> NO_LENS
        target == null -> unsupported(lens)
        else -> null
    }

    private fun onlyFlash(engine: CameraEngine, lens: LensInfo): String =
        "camera '${lens.id}' supports only ${engine.capabilities.value.supportedFlashModes(lens.id)} flash"

    private fun onlyTorch(engine: CameraEngine, lens: LensInfo): String =
        "camera '${lens.id}' supports only ${engine.capabilities.value.supportedTorchModes(lens.id)} torch"

    private fun fixedZoom(lens: LensInfo): String = "camera '${lens.id}' has a fixed zoom of ${lens.minZoom}"

    private fun skipCase(case: String, subInterface: String) {
        skips.record(case, "every step", "the driver is not a $subInterface")
    }

    private companion object {
        const val NO_LENS = "the platform reports no bound camera that the capabilities list"
    }

    private inner class Steps(private val case: String) {
        private var ran = 0

        suspend fun step(name: String, skipIf: String?, body: suspend () -> Unit) {
            if (skipIf != null) {
                skips.record(case, name, skipIf)
            } else {
                body()
                ran++
            }
        }

        fun requireSomethingRan() {
            if (ran == 0) fail("$case: nothing to verify on this engine")
        }
    }
}

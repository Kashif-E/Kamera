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

    // The reason is the platform's own mapping (CameraX RECOVERABLE, AVFoundation's interruption reasons), which
    // each platform's mapper table tests pin; the kit checks only that the engine is Interrupted.
    suspend fun interruptBeforeOpenThenEndInterruptionReachesOpenWithOneStartCall(scope: TestScope) {
        val case = "interruptBeforeOpenThenEndInterruptionReachesOpenWithOneStartCall"
        if (driver !is PreOpenInterruptionDriver) {
            return skips.record(case, "every step", "the engine has no interruption before Open")
        }
        withEngine { engine ->
            driver.holdOpen()
            val start = scope.async { engine.start(desired) }
            driver.settle()
            driver.interrupt(reason)
            driver.settle()

            assertEquals(success, start.await(), "a start the platform holds interrupted succeeds")
            assertIs<EngineState.Interrupted>(engine.state.value, "a start the platform holds interrupted")

            driver.endInterruption()
            driver.settle()
            assertEquals(
                EngineState.Open,
                engine.state.value,
                "the platform resumed the session without a second start",
            )
        }
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
            val selected = defaultCamera(engine)
            open(engine, selected?.let { CameraSelection.ById(it.id) } ?: desired.selection)
            val bound = driver.boundSettings()
            val lens = currentLens(engine)
            val steps = Steps(case)

            steps.step("reported camera", skipIf = noLens(lens)) {
                assertEquals(
                    selected?.id,
                    bound.cameraId,
                    "the platform bound another camera than the one the case selected",
                )
                assertEquals(
                    selected?.id,
                    engine.hardware.value.camera?.id,
                    "the reported camera is not the one the case selected",
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

    // Rule 5: a command reaches the platform, and hardware and zoom move only when the platform reports.
    suspend fun commandsLeaveHardwareUntilThePlatformReports() {
        val case = "commandsLeaveHardwareUntilThePlatformReports"
        withEngine { engine ->
            open(engine)
            val lens = currentLens(engine)
            val steps = Steps(case)

            // Runs on every engine with a bound camera, also one with nothing adjustable. On a camera with one
            // flash mode it shows the command is accepted while Open and moves nothing before a report; the
            // setFlashMode step below shows the routing where the camera has a second mode.
            val reported = engine.hardware.value.flashMode
            val toReported = "setFlashMode to the reported mode"
            steps.step(toReported, skipIf = noLens(lens)) {
                val change = HardwareChange(flashMode = reported)
                commandThenReport(engine, toReported, change, reported, { it.flashMode }) {
                    assertEquals(CameraKResult.Success(reported), engine.setFlashMode(reported), toReported)
                }
                assertEquals(reported, engine.hardware.value.flashMode, "hardware follows the platform's flash report")
            }
            val torch = torchTarget(engine, lens)
            steps.step("setTorchMode", skipIf = skipReason(lens, torch) { onlyTorch(engine, it) }) {
                commandThenReport(engine, "setTorchMode", HardwareChange(torchMode = torch), torch, { it.torchMode }) {
                    assertEquals(CameraKResult.Success(torch!!), engine.setTorchMode(torch))
                }
                assertEquals(torch, engine.hardware.value.torchMode, "hardware follows the platform's torch report")
            }
            val zoom = zoomTarget(engine, lens)
            steps.step("setZoom", skipIf = skipReason(lens, zoom) { fixedZoom(it) }) {
                commandThenReport(engine, "setZoom", HardwareChange(zoomRatio = zoom), zoom, { it.zoomRatio }) {
                    assertIs<CameraKResult.Success<*>>(engine.setZoom(zoom!!), "setZoom")
                }
                assertEquals(zoom, engine.zoom.value.ratio, "zoom follows the platform's zoom report")
            }
            val flash = lens?.let { reportedFlashTarget(engine, it) }
            steps.step("setFlashMode", skipIf = skipReason(lens, flash) { onlyFlash(engine, it) }) {
                commandThenReport(engine, "setFlashMode", HardwareChange(flashMode = flash), flash, { it.flashMode }) {
                    assertEquals(CameraKResult.Success(flash!!), engine.setFlashMode(flash))
                }
                assertEquals(flash, engine.hardware.value.flashMode, "hardware follows the platform's flash report")
            }
            steps.requireSomethingRan()
        }
    }

    // Issues [command], checks that it reached the platform as [applied] and that hardware and zoom stay where
    // the platform last put them, then reports [change].
    private suspend fun commandThenReport(
        engine: CameraEngine,
        name: String,
        change: HardwareChange,
        applied: Any?,
        bound: (BoundSettings) -> Any?,
        command: suspend () -> Unit,
    ) {
        val hardware = engine.hardware.value
        val zoom = engine.zoom.value
        command()
        driver.settle()
        assertEquals(applied, bound(driver.boundSettings()), "$name did not reach the platform")
        assertEquals(hardware, engine.hardware.value, "$name changed hardware before the platform reported it")
        assertEquals(zoom, engine.zoom.value, "$name changed zoom before the platform reported it")

        driver.changeHardware(change)
        driver.settle()
    }

    // HardwareState: no camera before the first bind, then the camera the engine opened on or switched to.
    suspend fun reportedCameraIsTheOneTheEngineOpenedOn() {
        val case = "reportedCameraIsTheOneTheEngineOpenedOn"
        withEngine { engine ->
            assertEquals(null, engine.hardware.value.camera, "a camera was reported before the first bind")
            val lenses = engine.capabilities.value.allLenses
            val steps = Steps(case)

            for (lens in lenses) {
                steps.step("reported camera after opening on '${lens.id}'", skipIf = null) {
                    assertEquals(
                        success,
                        engine.start(desiredOn(CameraSelection.ById(lens.id))),
                        "start on '${lens.id}'",
                    )
                    driver.settle()
                    assertEquals(
                        lens.id,
                        engine.hardware.value.camera?.id,
                        "the reported camera after opening on '${lens.id}'",
                    )
                    engine.stop()
                    driver.settle()
                }
            }
            val first = lenses.firstOrNull()
            val second = lenses.getOrNull(1)
            val switchSkip = if (first == null || second == null) "the engine has fewer than two cameras" else null
            steps.step("reported camera after a switch", skipIf = switchSkip) {
                open(engine, CameraSelection.ById(first!!.id))
                val switched = engine.selectCamera(CameraSelection.ById(second!!.id))
                assertEquals(second.id, assertIs<CameraKResult.Success<LensInfo>>(switched).value.id)
                driver.settle()
                assertEquals(
                    second.id,
                    engine.hardware.value.camera?.id,
                    "the reported camera after switching to '${second.id}'",
                )
            }
            steps.requireSomethingRan()
        }
    }

    // Rule 2: an interruption delivered while closed belongs to no live generation, so the next start opens.
    suspend fun interruptWhileClosedDoesNotReachTheNextStart() = withEngine { engine ->
        open(engine)
        engine.stop()
        driver.settle()
        driver.interrupt(reason)
        driver.settle()
        assertEquals(EngineState.Closed, engine.state.value, "an interruption while closed changed the state")

        assertEquals(success, engine.start(desired), "start after an interruption delivered while closed")
        driver.settle()
        assertEquals(
            EngineState.Open,
            engine.state.value,
            "an interruption of the stopped generation reached the next one",
        )
    }

    suspend fun desiredHardwareReappliedAfterRestartAndCameraSwitch() {
        val case = "desiredHardwareReappliedAfterRestartAndCameraSwitch"
        withEngine { engine ->
            open(engine)
            val lens = currentLens(engine)
            val capabilities = engine.capabilities.value
            val other = lens?.let { current -> capabilities.allLenses.firstOrNull { it.id != current.id } }
            val flash = lens?.let { flashTarget(engine, it, other) }
            val torch = torchTarget(engine, lens, other)
            val zoom = zoomTarget(engine, lens, other)
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
            steps.step("camera switch", skipIf = if (other == null) "the engine has fewer than two cameras" else null) {
                val target = other!!
                val selected = engine.selectCamera(CameraSelection.ById(target.id))
                assertEquals(target.id, assertIs<CameraKResult.Success<LensInfo>>(selected).value.id)
                driver.settle()
                val bound = driver.boundSettings()
                assertEquals(target.id, bound.cameraId)

                val flashTaken = flash != null && flash in capabilities.supportedFlashModes(target.id)
                val flashSkip =
                    skipReason(lens, flash) { onlyFlash(engine, it) } ?: notTaken(target, "flash $flash", flashTaken)
                steps.step("flash after the switch", skipIf = flashSkip) {
                    assertEquals(flash, bound.flashMode, "flash after the switch")
                }
                val torchTaken = torch != null && torch in capabilities.supportedTorchModes(target.id)
                val torchSkip =
                    skipReason(lens, torch) { onlyTorch(engine, it) } ?: notTaken(target, "torch $torch", torchTaken)
                steps.step("torch after the switch", skipIf = torchSkip) {
                    assertEquals(torch, bound.torchMode, "torch after the switch")
                }
                val zoomTaken = zoom != null && zoom in target.minZoom..target.maxZoom
                val zoomSkip = skipReason(lens, zoom) { fixedZoom(it) } ?: notTaken(target, "zoom $zoom", zoomTaken)
                steps.step("zoom after the switch", skipIf = zoomSkip) {
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

    private suspend fun open(engine: CameraEngine, selection: CameraSelection = desired.selection) {
        assertEquals(success, engine.start(desiredOn(selection)), "start")
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

    private fun desiredOn(selection: CameraSelection) = DesiredHardware(
        selection = selection,
        flashMode = desired.flashMode,
        torchMode = desired.torchMode,
        zoomRatio = desired.zoomRatio,
        targetOrientation = desired.targetOrientation,
    )

    // The camera a case opens on when it does not name one: the first back camera, else the first listed.
    private fun defaultCamera(engine: CameraEngine): LensInfo? {
        val capabilities = engine.capabilities.value
        return capabilities.lenses(CameraLens.BACK).firstOrNull() ?: capabilities.allLenses.firstOrNull()
    }

    // The camera the platform bound, as the engine's capabilities describe it.
    private fun currentLens(engine: CameraEngine): LensInfo? {
        val id = driver.boundSettings().cameraId ?: return null
        return engine.capabilities.value.allLenses.firstOrNull { it.id == id }
    }

    // Each target is a value the bound camera takes, preferring one the [other] camera takes as well, so a
    // camera switch can check that it is re-applied.
    private fun flashTarget(engine: CameraEngine, lens: LensInfo, other: LensInfo? = null): FlashMode? {
        val capabilities = engine.capabilities.value
        val candidates = listOf(FlashMode.ON, FlashMode.AUTO, FlashMode.OFF)
            .filter { it != desired.flashMode && it in capabilities.supportedFlashModes(lens.id) }
        return candidates.firstOrNull { other != null && it in capabilities.supportedFlashModes(other.id) }
            ?: candidates.firstOrNull()
    }

    // A flash mode the bound camera takes other than the one the platform reports now.
    private fun reportedFlashTarget(engine: CameraEngine, lens: LensInfo): FlashMode? {
        val current = engine.hardware.value.flashMode
        return listOf(FlashMode.ON, FlashMode.AUTO, FlashMode.OFF)
            .firstOrNull { it != current && it in engine.capabilities.value.supportedFlashModes(lens.id) }
    }

    private fun torchTarget(engine: CameraEngine, lens: LensInfo?, other: LensInfo? = null): TorchMode? {
        lens ?: return null
        val capabilities = engine.capabilities.value
        val current = engine.hardware.value.torchMode
        val candidates = listOf(TorchMode.ON, TorchMode.AUTO, TorchMode.OFF)
            .filter { it != current && it in capabilities.supportedTorchModes(lens.id) }
        return candidates.firstOrNull { other != null && it in capabilities.supportedTorchModes(other.id) }
            ?: candidates.firstOrNull()
    }

    private fun zoomTarget(engine: CameraEngine, lens: LensInfo?, other: LensInfo? = null): Float? {
        lens ?: return null
        val current = engine.zoom.value.ratio
        val shared =
            other?.let { zoomInside(maxOf(lens.minZoom, it.minZoom), minOf(lens.maxZoom, it.maxZoom), current) }
        return shared ?: zoomInside(lens.minZoom, lens.maxZoom, current)
    }

    // A ratio inside [min, max] other than [current], or null when the range is a single value.
    private fun zoomInside(min: Float, max: Float, current: Float): Float? {
        if (max <= min) return null
        val middle = (min + max) / 2
        return if (middle != current) middle else min + (max - min) / 4
    }

    private fun notTaken(target: LensInfo, value: String, taken: Boolean): String? =
        if (taken) null else "camera '${target.id}' does not take $value"

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

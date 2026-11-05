@file:OptIn(CameraKEngineApi::class, ExperimentalCoroutinesApi::class)

package com.kashif.cameraK.recording

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.controls.toggleLens
import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.CameraEngineFactory
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.EngineRecordingEvent
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.plugin.CameraKPluginContext
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.COMMAND_DRAIN_TIMEOUT
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.FakeCapabilities
import com.kashif.cameraK.testing.FakeCaptures
import com.kashif.cameraK.testing.FakePermissionChecker
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoCaptureResult
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The holder's recording surface over [FakeCameraEngine]: the §5 state machine that
 * `RecordingController` owns, its single stop path, the microphone gate, camera switches refused
 * while a recording is live, and the recording step of a host stop and of close.
 */
class RecordingControllerTest {
    private fun TestScope.holderOver(
        engine: CameraEngine,
        environment: CameraKEnvironment = cameraKTestEnvironment(),
    ): CameraKStateHolder = CameraKStateHolder(
        CameraConfiguration(),
        CameraEngineFactory { _, _ ->
            engine
        },
        emptyList(),
        environment,
    )

    private fun TestScope.readyHolder(
        engine: CameraEngine,
        environment: CameraKEnvironment = cameraKTestEnvironment(),
    ): CameraKStateHolder {
        val holder = holderOver(engine, environment)
        holder.start()
        runCurrent()
        assertIs<CameraKState.Ready>(holder.cameraState.value, "the holder did not reach Ready")
        return holder
    }

    private fun TestScope.collectEvents(holder: CameraKStateHolder): List<CameraKEvent> {
        val received = mutableListOf<CameraKEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { holder.events.collect { received += it } }
        return received
    }

    private suspend fun CameraKStateHolder.recording(
        config: VideoConfiguration = VideoConfiguration(),
    ): RecordingState.Recording = assertIs<CameraKResult.Success<RecordingState.Recording>>(
        startRecording(config),
        "the recording did not start",
    ).value

    private fun assertFailure(expected: CameraKError, result: CameraKResult<*>, message: String? = null) {
        assertEquals(CameraKResult.Failure(expected), result, message)
    }

    private fun assertBusy(operation: CameraOperation, result: CameraKResult<*>, message: String? = null) {
        val error = assertIs<CameraKError.Busy>(assertIs<CameraKResult.Failure>(result, message).error, message)
        assertEquals(operation, error.operation, message)
    }

    private fun assertInvalid(operation: CameraOperation, result: CameraKResult<*>, message: String? = null) {
        val error = assertIs<CameraKError.InvalidState>(assertIs<CameraKResult.Failure>(result, message).error, message)
        assertEquals(operation, error.operation, message)
    }

    private fun FakeCameraEngine.count(call: String): Int = calls.count { it == call }

    @Test
    fun secondStartIsBusy() = runTest {
        val fake = FakeCameraEngine().apply { autoStartRecording = false }
        val holder = readyHolder(fake)

        val first = async { holder.startRecording() }
        runCurrent()
        assertIs<RecordingState.Starting>(holder.recordingState.value)
        assertBusy(CameraOperation.RECORD_START, holder.startRecording(), "a start while Starting")

        fake.deliverRecordingStarted()
        runCurrent()
        assertIs<CameraKResult.Success<RecordingState.Recording>>(first.await())
        assertBusy(CameraOperation.RECORD_START, holder.startRecording(), "a start while Recording")
        assertEquals(1, fake.count("startRecording"), "engine calls: ${fake.calls}")
        assertIs<RecordingState.Recording>(holder.recordingState.value)
    }

    @Test
    fun autoStopAndManualStopMakeOneEngineStopAndShareTheResult() = runTest {
        val fake = FakeCameraEngine().apply { autoFinalizeRecording = false }
        val holder = readyHolder(fake)
        val events = collectEvents(holder)
        val recording = holder.recording()

        val first = async { holder.stopRecording() }
        runCurrent()
        assertIs<RecordingState.Stopping>(holder.recordingState.value)
        val second = async { holder.stopRecording() }
        runCurrent()
        // The platform's own limit ends the recording while both stops wait for it.
        val video = FakeCaptures.video(path = recording.filePath)
        fake.finishRecording(CameraKResult.Success(video), RecordingEndReason.MAX_DURATION)
        runCurrent()

        assertEquals(CameraKResult.Success(video), first.await(), "the first stop")
        assertEquals(CameraKResult.Success(video), second.await(), "the second stop")
        assertEquals(1, fake.count("stopRecording"), "engine calls: ${fake.calls}")
        val finished = assertIs<RecordingState.Finished>(holder.recordingState.value)
        assertEquals(CameraKResult.Success(video), finished.result)
        assertEquals(recording.recordingId, finished.recordingId)
        assertEquals(emptyList(), events.filterIsInstance<CameraKEvent.RecordingEnded>(), "a stop the caller asked for")
    }

    @Test
    fun pauseWithoutSupportIsUnsupported() = runTest {
        val fake = FakeCameraEngine().apply { supportsPause = false }
        val holder = readyHolder(fake)
        assertFalse(holder.capabilities.value.supportsPauseResume)
        holder.recording()

        val result = holder.pauseRecording()

        val error = assertIs<CameraKError.Unsupported>(assertIs<CameraKResult.Failure>(result).error)
        assertEquals(CameraFeature.PAUSE_RESUME, error.feature)
        assertEquals(0, fake.count("pauseRecording"), "the engine was asked to pause")
        assertIs<RecordingState.Recording>(holder.recordingState.value)
    }

    @Test
    fun unsolicitedFinalizedGivesFinishedAndRecordingEnded() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)
        val events = collectEvents(holder)
        val recording = holder.recording()

        val video = FakeCaptures.video(path = recording.filePath)
        fake.finishRecording(CameraKResult.Success(video), RecordingEndReason.MAX_FILE_SIZE)
        runCurrent()

        val finished = assertIs<RecordingState.Finished>(holder.recordingState.value)
        assertEquals(recording.recordingId, finished.recordingId)
        assertEquals(CameraKResult.Success(video), finished.result)
        assertEquals(RecordingEndReason.MAX_FILE_SIZE, finished.reason)
        val ended = events.filterIsInstance<CameraKEvent.RecordingEnded>().single()
        assertEquals(recording.recordingId, ended.recordingId)
        assertEquals(CameraKResult.Success(video), ended.result)
        assertEquals(RecordingEndReason.MAX_FILE_SIZE, ended.reason)

        // The limit already ended it: a stop that arrives late does not reach the engine.
        assertInvalid(CameraOperation.RECORD_STOP, holder.stopRecording())
        assertEquals(0, fake.count("stopRecording"), "engine calls: ${fake.calls}")
        assertSame(finished, holder.recordingState.value, "the late stop changed the finished state")
    }

    @Test
    fun elapsedAcrossPauseAndResume() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)
        holder.recording()

        advanceTimeBy(2.seconds)
        assertEquals(CameraKResult.Success(Unit), holder.pauseRecording())
        val paused = assertIs<RecordingState.Paused>(holder.recordingState.value)
        assertEquals(2.seconds, paused.accumulated)
        advanceTimeBy(5.seconds)
        assertEquals(2.seconds, holder.recordingState.value.elapsed(), "time passed while paused")
        assertEquals(CameraKResult.Success(Unit), holder.resumeRecording())
        assertEquals(2.seconds, assertIs<RecordingState.Recording>(holder.recordingState.value).accumulated)
        advanceTimeBy(1.seconds)

        assertEquals(3.seconds, holder.recordingState.value.elapsed())
    }

    @Test
    fun closeMidRecordingStopsRecordingBeforeEngineStopOrClose() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)
        holder.recording()

        holder.closeAndJoin()

        val calls = fake.calls
        val stopRecording = calls.indexOf("stopRecording")
        assertTrue(stopRecording >= 0, "close did not stop the recording: $calls")
        assertTrue(stopRecording < calls.indexOf("close"), "the engine closed before the recording stopped: $calls")
        assertFalse("stop" in calls.subList(0, stopRecording), "the camera stopped before the recording: $calls")
    }

    @Test
    fun closeMidRecordingEmitsRecordingEndedShutdownWithPath() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)
        val events = collectEvents(holder)
        val recording = holder.recording()

        holder.closeAndJoin()

        val ended = events.filterIsInstance<CameraKEvent.RecordingEnded>().single()
        assertEquals(RecordingEndReason.SHUTDOWN, ended.reason)
        assertEquals(recording.recordingId, ended.recordingId)
        val video = assertIs<CameraKResult.Success<RecordedVideo>>(ended.result).value
        assertEquals(recording.filePath, video.path)
        val finished = assertIs<RecordingState.Finished>(holder.recordingState.value)
        assertEquals(RecordingEndReason.SHUTDOWN, finished.reason)
        assertEquals(recording.recordingId, finished.recordingId)
    }

    @Test
    fun hostStopDuringRecordingGoesThroughRecordingController() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)
        val events = collectEvents(holder)
        val recording = holder.recording()

        holder.stop()
        runCurrent()
        // No start came within the config-change grace.
        advanceTimeBy(CONFIG_CHANGE_GRACE)
        runCurrent()

        val calls = fake.calls
        val stopRecording = calls.indexOf("stopRecording")
        assertTrue(stopRecording >= 0, "the host stop did not stop the recording: $calls")
        assertTrue(stopRecording < calls.lastIndexOf("stop"), "the camera stopped before the recording: $calls")
        assertEquals(CameraKState.Idle, holder.cameraState.value)
        val finished = assertIs<RecordingState.Finished>(holder.recordingState.value)
        assertEquals(RecordingEndReason.INTERRUPTED, finished.reason)
        val ended = events.filterIsInstance<CameraKEvent.RecordingEnded>().single()
        assertEquals(RecordingEndReason.INTERRUPTED, ended.reason)
        assertEquals(recording.recordingId, ended.recordingId)
    }

    @Test
    fun enableAudioWithoutMicrophoneIsPermissionMicrophone() = runTest {
        val checker = FakePermissionChecker().apply {
            setStatus(CameraKPermission.MICROPHONE, PermissionStatus.Denied(shouldShowRationale = false))
        }
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake, cameraKTestEnvironment(checker))

        val result = holder.startRecording(VideoConfiguration(enableAudio = true))

        val error = assertIs<CameraKError.Permission>(assertIs<CameraKResult.Failure>(result).error)
        assertEquals(CameraKPermission.MICROPHONE, error.permission)
        assertEquals(PermissionStatus.Denied(shouldShowRationale = false), error.status)
        assertEquals(RecordingState.Idle, holder.recordingState.value)
        assertEquals(0, fake.count("startRecording"), "the engine started a recording without its microphone")

        val silent = holder.recording(VideoConfiguration(enableAudio = false))
        assertFalse(silent.hasAudio)
    }

    @Test
    fun selectCameraDuringEachRecordingStateIsBusyLens() = runTest {
        val fake = FakeCameraEngine().apply {
            autoStartRecording = false
            autoFinalizeRecording = false
        }
        val holder = readyHolder(fake)

        suspend fun assertSwitchRefused(state: String) {
            assertBusy(CameraOperation.LENS, holder.selectCamera(CameraLens.FRONT), "selectCamera(lens) while $state")
            assertBusy(
                CameraOperation.LENS,
                holder.selectCamera(FakeCapabilities.FRONT_ID),
                "selectCamera(id) while $state",
            )
            assertBusy(CameraOperation.LENS, holder.toggleLens(), "toggleLens while $state")
        }

        val start = async { holder.startRecording() }
        runCurrent()
        assertIs<RecordingState.Starting>(holder.recordingState.value)
        assertSwitchRefused("Starting")

        fake.deliverRecordingStarted()
        runCurrent()
        assertIs<CameraKResult.Success<RecordingState.Recording>>(start.await())
        assertSwitchRefused("Recording")

        assertEquals(CameraKResult.Success(Unit), holder.pauseRecording())
        assertIs<RecordingState.Paused>(holder.recordingState.value)
        assertSwitchRefused("Paused")

        val stop = async { holder.stopRecording() }
        runCurrent()
        assertIs<RecordingState.Stopping>(holder.recordingState.value)
        assertSwitchRefused("Stopping")

        fake.deliverRecordingFinalized()
        runCurrent()
        assertIs<CameraKResult.Success<RecordedVideo>>(stop.await())
        assertEquals(0, fake.count("selectCamera"), "a switch reached the engine: ${fake.calls}")
        assertIs<CameraKResult.Success<LensInfo>>(holder.selectCamera(CameraLens.FRONT), "a switch after the end")
    }

    @Test
    fun deviceTypeOnlySwitchDuringRecordingIsBusyDeviceType() = runTest {
        val offered = FakeCapabilities.backAndFront()
        val ultraWide = LensInfo(
            id = "back-ultra-wide",
            deviceType = CameraDeviceType.ULTRA_WIDE,
            lens = CameraLens.BACK,
            minZoom = 1f,
            maxZoom = 2f,
            hasFlash = false,
            isLogical = false,
        )
        val withUltraWide = CameraCapabilities(
            allLenses = offered.allLenses + ultraWide,
            flashModes = offered.allLenses.associate { it.id to offered.supportedFlashModes(it.id) },
            torchModes = offered.allLenses.associate { it.id to offered.supportedTorchModes(it.id) },
            supportsPauseResume = offered.supportsPauseResume,
            supportedVideoQualities = offered.supportedVideoQualities,
            supportsVideoBitrate = offered.supportsVideoBitrate,
            supportedAspectRatios = offered.supportedAspectRatios,
            supportedImageFormats = offered.supportedImageFormats,
            supportedAnalysisFormats = offered.supportedAnalysisFormats,
            supportsSquareCapture = offered.supportsSquareCapture,
        )
        val fake = FakeCameraEngine(capabilities = withUltraWide)
        val holder = readyHolder(fake)
        assertEquals(FakeCapabilities.BACK_ID, fake.appliedCameraId)
        holder.recording()

        assertBusy(
            CameraOperation.DEVICE_TYPE,
            holder.selectCamera(CameraLens.BACK, CameraDeviceType.ULTRA_WIDE),
            "the same lens, another device type",
        )
        assertBusy(CameraOperation.DEVICE_TYPE, holder.selectCamera(ultraWide.id), "the ultra-wide camera by id")
        assertBusy(
            CameraOperation.LENS,
            holder.selectCamera(CameraLens.FRONT, CameraDeviceType.WIDE_ANGLE),
            "another lens, the same device type",
        )
        assertBusy(CameraOperation.LENS, holder.selectCamera(FakeCapabilities.FRONT_ID), "the front camera by id")
        assertEquals(0, fake.count("selectCamera"), "a switch reached the engine: ${fake.calls}")
    }

    @Test
    fun finalizeBeforeStartFailsStartRecording() = runTest {
        val fake = FakeCameraEngine().apply { autoStartRecording = false }
        val holder = readyHolder(fake)
        val events = collectEvents(holder)

        val start = async { holder.startRecording() }
        runCurrent()
        val error = CameraKError.Hardware(platformCode = 7)
        fake.finishRecording(CameraKResult.Failure(error), RecordingEndReason.ERROR)
        runCurrent()

        assertSame(error, assertIs<CameraKResult.Failure>(start.await()).error)
        assertEquals(RecordingState.Idle, holder.recordingState.value)
        assertEquals(
            emptyList(),
            events.filterIsInstance<CameraKEvent.RecordingEnded>(),
            "a start's failure is its result",
        )
    }

    @Test
    fun startedThenFinalizedBeforeTheStartResumesStaysFinished() = runTest {
        val fake = FakeCameraEngine().apply { autoStartRecording = false }
        val holder = readyHolder(fake)
        val events = collectEvents(holder)

        val start = async { holder.startRecording() }
        runCurrent()
        // Android: VideoRecordEvent.Start, then at once an error Finalize, both before the start resumes.
        val error = CameraKError.Hardware(platformCode = 7)
        fake.deliverRecordingStarted()
        fake.finishRecording(CameraKResult.Failure(error), RecordingEndReason.ERROR)
        runCurrent()

        val started = assertIs<CameraKResult.Success<RecordingState.Recording>>(start.await()).value
        val finished = assertIs<RecordingState.Finished>(holder.recordingState.value, "the ended recording came back")
        assertEquals(started.recordingId, finished.recordingId)
        assertEquals(CameraKResult.Failure(error), finished.result)
        assertEquals(RecordingEndReason.ERROR, finished.reason)
        val ended = events.filterIsInstance<CameraKEvent.RecordingEnded>().single()
        assertEquals(started.recordingId, ended.recordingId)
        assertEquals(RecordingEndReason.ERROR, ended.reason)

        advanceTimeBy(1.seconds)
        runCurrent()
        @Suppress("DEPRECATION")
        val shown = holder.uiState.value.let { it.isRecording to it.recordingDurationMs }
        assertEquals(false to 0L, shown, "uiState's isRecording and recordingDurationMs a second after the end")
        assertSame(finished, holder.recordingState.value, "the state moved after the end")

        fake.autoStartRecording = true
        val next = holder.startRecording()
        assertIs<CameraKResult.Success<RecordingState.Recording>>(next, "a new start after the end: $next")
    }

    /** Reports the recording's end before any Started, then returns Success: the order §5's Finalized row covers. */
    private class EndsBeforeStartedEngine(
        val fake: FakeCameraEngine,
        private val end: EngineRecordingEvent.Finalized,
    ) : CameraEngine by fake {
        private val ends = MutableSharedFlow<EngineRecordingEvent>(extraBufferCapacity = 1)
        override val recordingEvents: Flow<EngineRecordingEvent> = merge(fake.recordingEvents, ends)

        override suspend fun startRecording(config: VideoConfiguration): CameraKResult<String> {
            ends.emit(end)
            return CameraKResult.Success("/fake/ended.mp4")
        }
    }

    @Test
    fun finalizedFailureBeforeStartedFailsStartRecordingAfterTheEngineReturned() = runTest {
        val error = CameraKError.Hardware(platformCode = 7)
        val engine = EndsBeforeStartedEngine(
            FakeCameraEngine(),
            EngineRecordingEvent.Finalized(CameraKResult.Failure(error), RecordingEndReason.ERROR),
        )
        val holder = readyHolder(engine)
        val events = collectEvents(holder)

        val start = holder.startRecording()

        assertEquals(CameraKResult.Failure(error), start, "§5: Finalized before Started fails startRecording")
        assertEquals(RecordingState.Idle, holder.recordingState.value)
        assertEquals(
            emptyList(),
            events.filterIsInstance<CameraKEvent.RecordingEnded>(),
            "a start's failure is its result",
        )
    }

    @Test
    fun finalizedWithAPlayableFileBeforeStartedReturnsTheFile() = runTest {
        val fake = FakeCameraEngine().apply { autoStartRecording = false }
        val holder = readyHolder(fake)
        val events = collectEvents(holder)

        val start = async { holder.startRecording() }
        runCurrent()
        val video = FakeCaptures.video(path = "/fake/VID_0001.mp4")
        fake.finishRecording(CameraKResult.Success(video), RecordingEndReason.MAX_DURATION)
        runCurrent()

        // §5 partial files: a recording that ends with a playable file returns it, whatever ended it.
        val started = assertIs<CameraKResult.Success<RecordingState.Recording>>(start.await()).value
        val finished = assertIs<RecordingState.Finished>(holder.recordingState.value)
        assertEquals(started.recordingId, finished.recordingId)
        assertEquals(CameraKResult.Success(video), finished.result)
        assertEquals(RecordingEndReason.MAX_DURATION, finished.reason)
        val ended = events.filterIsInstance<CameraKEvent.RecordingEnded>().single()
        assertEquals(CameraKResult.Success(video), ended.result)
    }

    @Test
    fun startNotConfirmedInTimeIsTimeoutRecordStart() = runTest {
        val fake = FakeCameraEngine().apply { autoStartRecording = false }
        val holder = readyHolder(fake)

        val start = async { holder.startRecording() }
        runCurrent()
        advanceTimeBy(RECORDING_START_TIMEOUT - 1.milliseconds)
        runCurrent()
        assertFalse(start.isCompleted, "the start gave up before RECORDING_START_TIMEOUT")
        advanceTimeBy(1.milliseconds)
        runCurrent()

        val error = assertIs<CameraKError.Timeout>(assertIs<CameraKResult.Failure>(start.await()).error)
        assertEquals(CameraOperation.RECORD_START, error.operation)
        assertEquals(RecordingState.Idle, holder.recordingState.value)
        val calls = fake.calls
        assertTrue(
            calls.lastIndexOf("stopRecording") > calls.indexOf("startRecording"),
            "the engine's unconfirmed recording was left running: $calls",
        )
    }

    @Test
    fun recordingIdMatchesAcrossStateEventAndResult() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)
        val events = collectEvents(holder)

        val first = holder.recording()
        assertEquals(first.recordingId, assertIs<RecordingState.Recording>(holder.recordingState.value).recordingId)
        fake.finishRecording(
            CameraKResult.Success(FakeCaptures.video(path = first.filePath)),
            RecordingEndReason.MAX_DURATION,
        )
        runCurrent()
        assertEquals(first.recordingId, assertIs<RecordingState.Finished>(holder.recordingState.value).recordingId)
        assertEquals(first.recordingId, events.filterIsInstance<CameraKEvent.RecordingEnded>().single().recordingId)

        val second = holder.recording()
        assertTrue(second.recordingId > first.recordingId, "ids: ${first.recordingId}, ${second.recordingId}")
        assertIs<CameraKResult.Success<RecordedVideo>>(holder.stopRecording())
        val finished = assertIs<RecordingState.Finished>(holder.recordingState.value)
        assertEquals(second.recordingId, finished.recordingId)
        assertEquals(RecordingEndReason.USER, finished.reason)
    }

    @Test
    fun prepareDuringRecordingIsBusy() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)

        assertEquals(CameraKResult.Success(Unit), holder.prepareRecording(), "prepare while Idle")
        holder.recording()

        assertBusy(CameraOperation.RECORD_PREPARE, holder.prepareRecording(), "prepare while Recording")
        assertEquals(1, fake.count("prepareRecording"), "engine calls: ${fake.calls}")
    }

    @Test
    fun startRecordingBeforeReadyIsNotReady() = runTest {
        val fake = FakeCameraEngine().apply { autoOpen = false }
        val holder = holderOver(fake)
        val events = collectEvents(holder)

        assertFailure(CameraKError.NotReady, holder.startRecording(), "before start")
        holder.start()
        runCurrent()
        assertEquals(CameraKState.Starting, holder.cameraState.value)
        assertFailure(CameraKError.NotReady, holder.startRecording(), "while Starting")
        assertFailure(CameraKError.NotReady, holder.prepareRecording(), "prepare while Starting")
        assertFailure(CameraKError.NotReady, holder.stopRecording(), "stop while Starting")
        assertFailure(CameraKError.NotReady, holder.pauseRecording(), "pause while Starting")
        assertFailure(CameraKError.NotReady, holder.resumeRecording(), "resume while Starting")

        assertEquals(RecordingState.Idle, holder.recordingState.value)
        assertEquals(emptyList(), events, "a refused recording emitted an event")
        assertEquals(listOf("start"), fake.calls, "a recording call reached the engine before Ready")
    }

    /** Records the dispatcher each recording call runs on, with the fake underneath. */
    private class DispatcherRecordingEngine(val fake: FakeCameraEngine) : CameraEngine by fake {
        var prepareDispatcher: CoroutineDispatcher? = null
        var startDispatcher: CoroutineDispatcher? = null

        override suspend fun prepareRecording(config: VideoConfiguration): CameraKResult<Unit> {
            prepareDispatcher = currentCoroutineContext()[ContinuationInterceptor] as? CoroutineDispatcher
            return fake.prepareRecording(config)
        }

        override suspend fun startRecording(config: VideoConfiguration): CameraKResult<String> {
            startDispatcher = currentCoroutineContext()[ContinuationInterceptor] as? CoroutineDispatcher
            return fake.startRecording(config)
        }
    }

    @Test
    fun recordingCallsReachTheEngineOnTheDefaultDispatcher() = runTest {
        val main = StandardTestDispatcher(testScheduler, name = "main")
        val default = StandardTestDispatcher(testScheduler, name = "default")
        val environment = CameraKEnvironment(
            dispatchers = CameraKDispatchers(main = main, default = default, io = default),
            timeSource = testScheduler.timeSource,
            parentJob = SupervisorJob(backgroundScope.coroutineContext.job),
            permissionChecker = FakePermissionChecker(),
        )
        val engine = DispatcherRecordingEngine(FakeCameraEngine())
        val holder = readyHolder(engine, environment)

        assertEquals(CameraKResult.Success(Unit), holder.prepareRecording())
        holder.recording()

        assertSame(default, engine.prepareDispatcher, "prepareRecording ran on ${engine.prepareDispatcher}")
        assertSame(default, engine.startDispatcher, "startRecording ran on ${engine.startDispatcher}")
    }

    /**
     * The fake underneath, with a platform's timing scripted on top: Finalized events held back and
     * delivered late, and one stop or pause replaced, for instance by a call that never answers.
     */
    private class ScriptedRecordingEngine(val fake: FakeCameraEngine) : CameraEngine by fake {
        private val late = MutableSharedFlow<EngineRecordingEvent>(extraBufferCapacity = 16)
        private val held = mutableListOf<EngineRecordingEvent.Finalized>()
        private val closed = CompletableDeferred<Unit>()

        /** True: each Finalized the fake emits is held until [deliverHeld]. */
        var holdFinalized = false

        /** Runs once in place of the next stopRecording. */
        var nextStop: (suspend () -> CameraKResult<RecordedVideo>)? = null

        /** Runs once in place of the next pauseRecording. */
        var nextPause: (suspend () -> CameraKResult<Unit>)? = null

        override val recordingEvents: Flow<EngineRecordingEvent> = merge(
            fake.recordingEvents.transform { event ->
                if (holdFinalized && event is EngineRecordingEvent.Finalized) held += event else emit(event)
            },
            late,
        )

        fun deliverHeld() {
            held.forEach { late.tryEmit(it) }
            held.clear()
        }

        /** A platform call that never answers: it returns only once the engine closes. */
        suspend fun <T> untilClosed(): CameraKResult<T> {
            closed.await()
            return CameraKResult.Failure(CameraKError.Closed)
        }

        override suspend fun stopRecording(): CameraKResult<RecordedVideo> =
            nextStop?.also { nextStop = null }?.invoke() ?: fake.stopRecording()

        override suspend fun pauseRecording(): CameraKResult<Unit> =
            nextPause?.also { nextPause = null }?.invoke() ?: fake.pauseRecording()

        override fun close() {
            closed.complete(Unit)
            fake.close()
        }

        override suspend fun closeAndJoin() {
            closed.complete(Unit)
            fake.closeAndJoin()
        }
    }

    @Test
    fun aLateFinalizedOfAStoppedRecordingDoesNotEndTheNextOne() = runTest {
        val engine = ScriptedRecordingEngine(FakeCameraEngine()).apply { holdFinalized = true }
        val holder = readyHolder(engine)
        val first = holder.recording()
        assertIs<CameraKResult.Success<RecordedVideo>>(holder.stopRecording())
        runCurrent()
        val second = holder.recording()

        // The platform reports the first recording's end only now.
        engine.deliverHeld()
        runCurrent()

        val live = assertIs<RecordingState.Recording>(
            holder.recordingState.value,
            "the first recording's late Finalized ended the second",
        )
        assertEquals(second.recordingId, live.recordingId)
        engine.holdFinalized = false
        val stopped = assertIs<CameraKResult.Success<RecordedVideo>>(holder.stopRecording(), "the second stop").value
        assertEquals(second.filePath, stopped.path)
        assertTrue(stopped.path != first.filePath, "both recordings wrote ${first.filePath}")
    }

    @Test
    fun aLateFinalizedThatLandsWhileTheNextStartIsStartingIsNotItsEnd() = runTest {
        val fake = FakeCameraEngine()
        val engine = ScriptedRecordingEngine(fake).apply { holdFinalized = true }
        val holder = readyHolder(engine)
        val first = holder.recording()
        assertIs<CameraKResult.Success<RecordedVideo>>(holder.stopRecording())
        runCurrent()
        engine.holdFinalized = false
        fake.autoStartRecording = false

        val start = async { holder.startRecording() }
        runCurrent()
        assertIs<RecordingState.Starting>(holder.recordingState.value)
        engine.deliverHeld()
        runCurrent()
        fake.deliverRecordingStarted()
        runCurrent()

        val second = assertIs<CameraKResult.Success<RecordingState.Recording>>(start.await()).value
        assertTrue(second.filePath != first.filePath, "the second start returned the first recording's file")
        val live = assertIs<RecordingState.Recording>(
            holder.recordingState.value,
            "the first recording's late Finalized ended the second start",
        )
        assertEquals(second.recordingId, live.recordingId)
        val stopped = assertIs<CameraKResult.Success<RecordedVideo>>(holder.stopRecording(), "the second stop").value
        assertEquals(second.filePath, stopped.path)
    }

    @Test
    fun aLateFinalizedOfATimedOutStartIsDrained() = runTest {
        val fake = FakeCameraEngine().apply { autoStartRecording = false }
        val engine = ScriptedRecordingEngine(fake).apply { holdFinalized = true }
        val holder = readyHolder(engine)
        // The platform had begun the start the holder gave up on: the stop ends it, and its Finalized comes late.
        val abandoned = FakeCaptures.video(path = "/fake/VID_0001.mp4")
        engine.nextStop = {
            fake.finishRecording(CameraKResult.Success(abandoned), RecordingEndReason.USER)
            CameraKResult.Success(abandoned)
        }

        val start = async { holder.startRecording() }
        runCurrent()
        advanceTimeBy(RECORDING_START_TIMEOUT)
        runCurrent()
        assertIs<CameraKError.Timeout>(assertIs<CameraKResult.Failure>(start.await()).error)
        assertEquals(RecordingState.Idle, holder.recordingState.value)
        fake.autoStartRecording = true
        val second = holder.recording()
        engine.deliverHeld()
        runCurrent()

        val live = assertIs<RecordingState.Recording>(
            holder.recordingState.value,
            "the abandoned start's late Finalized ended the next recording",
        )
        assertEquals(second.recordingId, live.recordingId)
    }

    @Test
    fun aUserStopTheEngineNeverAnswersEndsAsTimeoutAndItsLateFinalizedIsDrained() = runTest {
        val fake = FakeCameraEngine()
        val engine = ScriptedRecordingEngine(fake).apply { holdFinalized = true }
        val holder = readyHolder(engine)
        val events = collectEvents(holder)
        val first = holder.recording()
        engine.nextStop = { engine.untilClosed() }

        val stop = async { holder.stopRecording() }
        runCurrent()
        advanceTimeBy(SHUTDOWN_STOP_TIMEOUT - 1.milliseconds)
        runCurrent()
        assertFalse(stop.isCompleted, "the stop gave up before SHUTDOWN_STOP_TIMEOUT")
        advanceTimeBy(1.milliseconds)
        runCurrent()

        assertTrue(stop.isCompleted, "the stop still waits on the engine: ${holder.recordingState.value}")
        val error = assertIs<CameraKError.Timeout>(assertIs<CameraKResult.Failure>(stop.await()).error)
        assertEquals(CameraOperation.RECORD_STOP, error.operation)
        val finished = assertIs<RecordingState.Finished>(holder.recordingState.value)
        assertEquals(first.recordingId, finished.recordingId)
        assertEquals(RecordingEndReason.ERROR, finished.reason)
        assertIs<CameraKError.Timeout>(assertIs<CameraKResult.Failure>(finished.result).error)
        val ended = events.filterIsInstance<CameraKEvent.RecordingEnded>().single()
        assertEquals(first.recordingId, ended.recordingId)
        assertEquals(RecordingEndReason.ERROR, ended.reason)

        // The platform finalizes the first recording after all, once the second is live.
        fake.finishRecording(
            CameraKResult.Success(FakeCaptures.video(path = first.filePath)),
            RecordingEndReason.USER,
        )
        runCurrent()
        val second = holder.recording()
        engine.deliverHeld()
        runCurrent()
        val live = assertIs<RecordingState.Recording>(
            holder.recordingState.value,
            "the timed-out stop's late Finalized ended the next recording",
        )
        assertEquals(second.recordingId, live.recordingId)
    }

    @Test
    fun aFinalizedFromInsideStopRecordingEndsTheRecordingOnce() = runTest {
        val fake = FakeCameraEngine()
        val engine = ScriptedRecordingEngine(fake)
        val holder = readyHolder(engine)
        val events = collectEvents(holder)
        val states = mutableListOf<RecordingState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            holder.recordingState.collect { states += it }
        }
        val first = holder.recording()
        val video = FakeCaptures.video(path = first.filePath)
        // The platform reaches its limit as the stop arrives: its one Finalized reaches the holder
        // from inside stopRecording, before the call returns.
        engine.nextStop = {
            fake.finishRecording(CameraKResult.Success(video), RecordingEndReason.MAX_DURATION)
            delay(1.milliseconds)
            CameraKResult.Success(video)
        }

        val stopped = holder.stopRecording()

        assertEquals(CameraKResult.Success(video), stopped)
        val finished = assertIs<RecordingState.Finished>(holder.recordingState.value)
        assertEquals(1, states.count { it is RecordingState.Finished }, "terminal states: $states")
        assertEquals(RecordingEndReason.USER, finished.reason, "the stop held the lock first; its outcome stands")
        assertEquals(stopped, finished.result)
        assertEquals(emptyList(), events.filterIsInstance<CameraKEvent.RecordingEnded>(), "a stop the caller asked for")

        // That Finalized was the first recording's own: the next recording's platform end is still its.
        val second = holder.recording()
        val limit = FakeCaptures.video(path = second.filePath)
        fake.finishRecording(CameraKResult.Success(limit), RecordingEndReason.MAX_FILE_SIZE)
        runCurrent()
        val next = assertIs<RecordingState.Finished>(holder.recordingState.value, "the next recording's end was lost")
        assertEquals(second.recordingId, next.recordingId)
        assertEquals(RecordingEndReason.MAX_FILE_SIZE, next.reason)
    }

    @Test
    fun hostStopGivesUpOnTheRecordingAfterShutdownStopTimeout() = runTest {
        val fake = FakeCameraEngine().apply { autoStartRecording = false }
        val engine = ScriptedRecordingEngine(fake)
        val holder = readyHolder(engine)
        // The start is never confirmed and the stop of it never answers, so the recording step cannot end.
        engine.nextStop = { engine.untilClosed() }
        val start = async { holder.startRecording() }
        runCurrent()
        advanceTimeBy(1.seconds)

        holder.stop()
        runCurrent()
        // No start came within the config-change grace; the recording step starts after it.
        advanceTimeBy(CONFIG_CHANGE_GRACE)
        runCurrent()
        advanceTimeBy(SHUTDOWN_STOP_TIMEOUT - 1.milliseconds)
        runCurrent()
        assertEquals(0, fake.stopCalls, "the host stop gave up before SHUTDOWN_STOP_TIMEOUT: ${fake.calls}")
        advanceTimeBy(1.milliseconds)
        runCurrent()

        assertEquals(1, fake.stopCalls, "the host stop waited past SHUTDOWN_STOP_TIMEOUT: ${fake.calls}")
        start.cancel()
    }

    @Test
    fun closeGivesUpOnTheRecordingAfterShutdownStopTimeout() = runTest {
        val fake = FakeCameraEngine()
        val engine = ScriptedRecordingEngine(fake)
        val holder = readyHolder(engine)
        holder.recording()
        // A pause the platform never answers holds the recording's transitions, so close's stop waits behind it.
        engine.nextPause = { engine.untilClosed() }
        val pause = async { holder.pauseRecording() }
        runCurrent()

        holder.close()
        runCurrent()
        // After the recording step, close lets the running commands drain before it closes the engine.
        advanceTimeBy(SHUTDOWN_STOP_TIMEOUT + COMMAND_DRAIN_TIMEOUT - 1.milliseconds)
        runCurrent()
        assertEquals(0, fake.closeCalls, "close gave up on the recording before SHUTDOWN_STOP_TIMEOUT")
        advanceTimeBy(1.milliseconds)
        runCurrent()

        assertEquals(1, fake.closeCalls, "close waited past SHUTDOWN_STOP_TIMEOUT for the recording: ${fake.calls}")
        assertEquals(CameraKResult.Failure(CameraKError.Closed), pause.await())
    }

    @Test
    fun aPlatformEndWhilePausedGivesFinishedAndRecordingEnded() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)
        val events = collectEvents(holder)
        val recording = holder.recording()
        assertEquals(CameraKResult.Success(Unit), holder.pauseRecording())
        assertIs<RecordingState.Paused>(holder.recordingState.value)

        val video = FakeCaptures.video(path = recording.filePath)
        fake.finishRecording(CameraKResult.Success(video), RecordingEndReason.MAX_FILE_SIZE)
        runCurrent()

        val finished = assertIs<RecordingState.Finished>(holder.recordingState.value, "a paused recording's end")
        assertEquals(recording.recordingId, finished.recordingId)
        assertEquals(CameraKResult.Success(video), finished.result)
        assertEquals(RecordingEndReason.MAX_FILE_SIZE, finished.reason)
        val ended = events.filterIsInstance<CameraKEvent.RecordingEnded>().single()
        assertEquals(recording.recordingId, ended.recordingId)
        assertEquals(RecordingEndReason.MAX_FILE_SIZE, ended.reason)
    }

    @Test
    fun hostStopDuringStartingWaitsForTheStartThenStopsItAsInterrupted() = runTest {
        val fake = FakeCameraEngine().apply { autoStartRecording = false }
        val holder = readyHolder(fake)
        val events = collectEvents(holder)
        val start = async { holder.startRecording() }
        runCurrent()
        assertIs<RecordingState.Starting>(holder.recordingState.value)

        holder.stop()
        runCurrent()
        assertEquals(0, fake.stopCalls, "the camera stopped under a start still resolving: ${fake.calls}")
        fake.deliverRecordingStarted()
        runCurrent()
        // No start came within the config-change grace.
        advanceTimeBy(CONFIG_CHANGE_GRACE)
        runCurrent()

        val started = assertIs<CameraKResult.Success<RecordingState.Recording>>(start.await(), "the start").value
        val calls = fake.calls
        assertTrue(
            calls.indexOf("stopRecording") in 0 until calls.indexOf("stop"),
            "the host stop did not stop the recording before the camera: $calls",
        )
        val finished = assertIs<RecordingState.Finished>(holder.recordingState.value)
        assertEquals(started.recordingId, finished.recordingId)
        assertEquals(RecordingEndReason.INTERRUPTED, finished.reason)
        val ended = events.filterIsInstance<CameraKEvent.RecordingEnded>().single()
        assertEquals(started.recordingId, ended.recordingId)
        assertEquals(RecordingEndReason.INTERRUPTED, ended.reason)
        assertEquals(CameraKState.Idle, holder.cameraState.value)
    }

    @Test
    fun pluginContextRecordingStateIsTheHolders() = runTest {
        var context: CameraKPluginContext? = null
        val plugin = object : CameraKPlugin {
            override val needsFrames: Boolean = false

            override fun onAttach(attached: CameraKPluginContext) {
                context = attached
            }

            override fun onDetach() = Unit
        }
        val fake = FakeCameraEngine().apply { autoStartRecording = false }
        val holder = CameraKStateHolder(
            CameraConfiguration(),
            CameraEngineFactory { _, _ -> fake },
            listOf(plugin),
            cameraKTestEnvironment(),
        )
        holder.start()
        runCurrent()
        assertIs<CameraKState.Ready>(holder.cameraState.value)
        val seen = mutableListOf<RecordingState>()
        val pluginContext = assertNotNull(context, "the plugin was not attached")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            pluginContext.recordingState.collect { seen += it }
        }

        val start = async { holder.startRecording() }
        runCurrent()
        assertIs<RecordingState.Starting>(pluginContext.recordingState.value, "the plugin's view while Starting")
        fake.deliverRecordingStarted()
        runCurrent()
        assertIs<CameraKResult.Success<RecordingState.Recording>>(start.await())
        assertSame(holder.recordingState.value, pluginContext.recordingState.value, "the plugin's view while Recording")
        assertIs<CameraKResult.Success<RecordedVideo>>(holder.stopRecording())
        runCurrent()

        assertSame(holder.recordingState.value, pluginContext.recordingState.value, "the plugin's view once Finished")
        assertEquals(
            listOf("Idle", "Starting", "Recording", "Finished"),
            seen.map { it::class.simpleName }.filter { it != "Stopping" },
            "what the plugin saw",
        )
    }

    @Test
    @Suppress("DEPRECATION")
    fun theDeprecatedRecordingEventsFireBesideTheNewOnes() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)
        val events = collectEvents(holder)
        var from = 0
        fun legacy() = events.drop(from).filter {
            it is CameraKEvent.RecordingStarted ||
                it is CameraKEvent.RecordingStopped ||
                it is CameraKEvent.RecordingFailed ||
                it is CameraKEvent.RecordingMaxDurationReached
        }

        // A stop the caller asked for, with a pause on the way: started once, stopped with the file.
        val first = holder.recording()
        assertEquals(CameraKResult.Success(Unit), holder.pauseRecording())
        assertEquals(CameraKResult.Success(Unit), holder.resumeRecording())
        val stopped = assertIs<CameraKResult.Success<RecordedVideo>>(holder.stopRecording()).value
        runCurrent()
        assertEquals(
            listOf(
                CameraKEvent.RecordingStarted(first.filePath),
                CameraKEvent.RecordingStopped(
                    VideoCaptureResult.Success(stopped.path, stopped.duration.inWholeMilliseconds),
                ),
            ),
            legacy(),
            "a user stop",
        )

        // The duration limit: the limit, then the file, beside RecordingEnded.
        from = events.size
        val second = holder.recording()
        val limited = FakeCaptures.video(path = second.filePath, duration = 30.seconds)
        fake.finishRecording(CameraKResult.Success(limited), RecordingEndReason.MAX_DURATION)
        runCurrent()
        assertEquals(
            listOf(
                CameraKEvent.RecordingStarted(second.filePath),
                CameraKEvent.RecordingMaxDurationReached(second.filePath, 30_000L),
                CameraKEvent.RecordingStopped(VideoCaptureResult.Success(second.filePath, 30_000L)),
            ),
            legacy(),
            "the duration limit",
        )
        assertEquals(
            RecordingEndReason.MAX_DURATION,
            events.drop(from).filterIsInstance<CameraKEvent.RecordingEnded>().single().reason,
        )

        // A platform error: the failure, beside RecordingEnded, and no path in its message.
        from = events.size
        val third = holder.recording()
        fake.finishRecording(CameraKResult.Failure(CameraKError.Hardware(platformCode = 7)), RecordingEndReason.ERROR)
        runCurrent()
        val shown = legacy()
        assertEquals(CameraKEvent.RecordingStarted(third.filePath), shown.first(), "the third start")
        val failed = assertIs<CameraKEvent.RecordingFailed>(shown.drop(1).single(), "the platform error: $shown")
        assertFalse(third.filePath in failed.exception.message.orEmpty(), "the failure names the file")
        assertEquals(
            RecordingEndReason.ERROR,
            events.drop(from).filterIsInstance<CameraKEvent.RecordingEnded>().single().reason,
        )
    }

    @Test
    @Suppress("DEPRECATION")
    fun aStartedThatArrivesWhileAnAbandonedStartIsStoppingLeavesTheStartAbandoned() = runTest {
        val fake = FakeCameraEngine().apply { autoStartRecording = false }
        val engine = ScriptedRecordingEngine(fake)
        val holder = readyHolder(engine)
        val events = collectEvents(holder)
        val states = mutableListOf<RecordingState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            holder.recordingState.collect { states += it }
        }
        // The stop of the start the holder gave up on is still out when the platform confirms that start.
        val stopAnswer = CompletableDeferred<CameraKResult<RecordedVideo>>()
        engine.nextStop = { stopAnswer.await() }

        val start = async { holder.startRecording() }
        runCurrent()
        advanceTimeBy(RECORDING_START_TIMEOUT)
        runCurrent()
        assertIs<RecordingState.Starting>(holder.recordingState.value, "the abandoned start's stop already returned")
        fake.deliverRecordingStarted()
        runCurrent()

        assertIs<RecordingState.Starting>(
            holder.recordingState.value,
            "a Started that arrived during the abandoned start's stop made it a recording",
        )
        stopAnswer.complete(CameraKResult.Success(FakeCaptures.video(path = "/fake/VID_0001.mp4")))
        runCurrent()

        assertIs<CameraKError.Timeout>(assertIs<CameraKResult.Failure>(start.await()).error)
        assertEquals(RecordingState.Idle, holder.recordingState.value)
        assertFalse(states.any { it is RecordingState.Recording }, "states: $states")
        assertEquals(emptyList(), events.filterIsInstance<CameraKEvent.RecordingStarted>(), "an abandoned start")
    }

    @Test
    @Suppress("DEPRECATION")
    fun aDurationLimitEndThatFailsStillReportsTheLimitWithThePathAndTheRecordedTime() = runTest {
        val fake = FakeCameraEngine()
        val holder = readyHolder(fake)
        val events = collectEvents(holder)
        val recording = holder.recording()
        advanceTimeBy(3.seconds)

        val failure = CameraKResult.Failure(CameraKError.Hardware(platformCode = 3))
        fake.finishRecording(failure, RecordingEndReason.MAX_DURATION)
        runCurrent()

        val legacy = events.filter {
            it is CameraKEvent.RecordingMaxDurationReached || it is CameraKEvent.RecordingFailed
        }
        assertEquals(2, legacy.size, "the 1.x events of a failed duration-limit end: $legacy")
        assertEquals(CameraKEvent.RecordingMaxDurationReached(recording.filePath, 3_000L), legacy[0])
        assertIs<CameraKEvent.RecordingFailed>(legacy[1])
        val ended = events.filterIsInstance<CameraKEvent.RecordingEnded>().single()
        assertEquals(RecordingEndReason.MAX_DURATION, ended.reason)
        assertIs<CameraKResult.Failure>(ended.result)
    }
}

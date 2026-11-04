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
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKEvent
import com.kashif.cameraK.state.CameraKState
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.FakeCapabilities
import com.kashif.cameraK.testing.FakeCaptures
import com.kashif.cameraK.testing.FakePermissionChecker
import com.kashif.cameraK.testing.cameraKTestEnvironment
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
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
}

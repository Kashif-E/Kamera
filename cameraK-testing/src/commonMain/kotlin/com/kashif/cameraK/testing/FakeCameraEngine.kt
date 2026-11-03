@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.testing

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineRecordingEvent
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.HardwareState
import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.result.map
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.ZoomState
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A scripted camera engine for tests: the test plays the platform. It follows the engine contract
 * (`CameraEngine`'s KDoc) and passes the contract kit, so a holder or plugin test over it sees what
 * a real engine would do.
 *
 * Nothing in its scripting surface names an opt-in type, so app tests use it without `@OptIn`.
 * Calling the engine members whose signatures name engine types (`state`, `hardware`, `start`,
 * `selectCamera`, `recordingEvents`, `preview`) needs the opt-in.
 *
 * Confined: call it from one thread. Under camerak-testing's `cameraKTestEnvironment()` everything
 * runs on the test scheduler's thread.
 *
 * @param capabilities What the fake platform offers; its `supportsPauseResume` seeds [supportsPause].
 */
public class FakeCameraEngine(
    capabilities: CameraCapabilities = FakeCapabilities.backAndFront(),
    initialFlash: FlashMode = FlashMode.OFF,
    initialTorch: TorchMode = TorchMode.OFF,
    initialZoom: Float = 1f,
) : CameraEngine {
    private val _state = MutableStateFlow<EngineState>(EngineState.Closed)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _capabilities = MutableStateFlow(capabilities)
    override val capabilities: StateFlow<CameraCapabilities> = _capabilities.asStateFlow()

    // The zoom range reported before the first open: the default camera's, the first back one.
    private val defaultCamera: LensInfo? = capabilities.lenses(CameraLens.BACK).firstOrNull()
        ?: capabilities.allLenses.firstOrNull()

    // No camera until the first bind reports one.
    private val _hardware = MutableStateFlow(HardwareState(initialFlash, initialTorch, null, null))
    override val hardware: StateFlow<HardwareState> = _hardware.asStateFlow()

    private val _zoom = MutableStateFlow(
        ZoomState(initialZoom, defaultCamera?.minZoom ?: initialZoom, defaultCamera?.maxZoom ?: initialZoom),
    )
    override val zoom: StateFlow<ZoomState> = _zoom.asStateFlow()

    private val _deviceOrientation = MutableStateFlow(DeviceOrientation.PORTRAIT)
    override val deviceOrientation: StateFlow<DeviceOrientation> = _deviceOrientation.asStateFlow()

    // No replay: a subscriber sees the milestones from its subscription on, as from a platform observer.
    private val _recordingEvents = MutableSharedFlow<EngineRecordingEvent>(extraBufferCapacity = EVENT_BUFFER)
    override val recordingEvents: Flow<EngineRecordingEvent> = _recordingEvents.asSharedFlow()

    /** The engine's frame source: [FakeFrameSource.emit] reaches its analyzers only while the engine is Open. */
    public val fakeFrames: FakeFrameSource = FakeFrameSource(
        sessionFormat = { configuration.analysisFormat },
        isOpen = { _state.value == EngineState.Open },
    )
    override val frames: FrameSource get() = fakeFrames
    override val preview: PlatformPreviewSource = fakePreviewSource()

    private val callLog = mutableListOf<String>()

    /** Every engine call, in order: "start", "stopRecording", "stop", "close" and so on. */
    public val calls: List<String> get() = callLog.toList()
    public val startCalls: Int get() = callLog.count { it == "start" }
    public val stopCalls: Int get() = callLog.count { it == "stop" }
    public val closeCalls: Int get() = callLog.count { it == "close" }
    public val setZoomCalls: Int get() = callLog.count { it == "setZoom" }

    /** False: start suspends until [deliverOpen], [failOpen] or [interrupt]. */
    public var autoOpen: Boolean = true

    /**
     * The configuration [start] checks: imageFormat, then analysisFormat, then aspectRatio against
     * the capabilities' supported sets, failing with `Unsupported(IMAGE_FORMAT / ANALYSIS_FORMAT /
     * ASPECT_RATIO)`.
     */
    public var configuration: CameraConfiguration = CameraConfiguration()

    /** What a capture returns; build values with [FakeCaptures.image]. */
    public var captureResult: suspend () -> CameraKResult<CapturedImage> = {
        CameraKResult.Success(FakeCaptures.image())
    }

    /** Published as `capabilities.supportsPauseResume`. */
    public var supportsPause: Boolean = capabilities.supportsPauseResume
        set(value) {
            field = value
            _capabilities.value = _capabilities.value.withPauseResume(value)
        }

    /** False: startRecording suspends until [deliverRecordingStarted], or until [finishRecording], whose result it returns. */
    public var autoStartRecording: Boolean = true

    /**
     * False: a requested end (stopRecording, stop, close) waits for [deliverRecordingFinalized],
     * which finalizes with Success and USER (stopRecording) or SHUTDOWN (stop, close).
     */
    public var autoFinalizeRecording: Boolean = true

    // What the fake platform last received: the desired hardware on each open and camera switch, and each successful setter.
    public var appliedFlashMode: FlashMode = initialFlash
        private set
    public var appliedTorchMode: TorchMode = initialTorch
        private set
    public var appliedZoomRatio: Float = initialZoom
        private set

    /** A `LensInfo.id`; null before the first open. */
    public var appliedCameraId: String? = null
        private set

    /** Native releases the fake performed: 1 after the first close, never more. */
    public var releaseCount: Int = 0
        private set

    private var closed = false
    private var desired: DesiredHardware? = null
    private var selectedCamera: LensInfo? = null
    private var pendingStart: CompletableDeferred<CameraKResult<Unit>>? = null
    private var pendingOpenFailure: CameraKError? = null
    private var unpublishedStartFailure: CameraKError? = null
    private var throwFromNext = false
    private var holdNext = false
    private var heldCapture: CompletableDeferred<Unit>? = null
    private var recording: ActiveRecording? = null
    private var recordingCount = 0
    private val released = CompletableDeferred<Unit>()

    override suspend fun start(desired: DesiredHardware): CameraKResult<Unit> {
        callLog += "start"
        if (closed) return CameraKResult.Failure(CameraKError.Closed)
        pendingStart?.let { return it.await() }
        val current = _state.value
        if (current == EngineState.Open || current is EngineState.Interrupted) return CameraKResult.Success(Unit)
        unpublishedStartFailure?.let {
            unpublishedStartFailure = null
            return CameraKResult.Failure(it)
        }
        unsupportedFeature()?.let { return failStart(CameraKError.Unsupported(it)) }
        val camera = resolve(desired.selection) ?: return failStart(CameraKError.NoCamera(lensOf(desired.selection)))

        this.desired = desired
        selectedCamera = camera
        _state.value = EngineState.Opening
        pendingOpenFailure?.let {
            pendingOpenFailure = null
            return failStart(it)
        }
        if (autoOpen) {
            applyDesired()
            _state.value = EngineState.Open
            return CameraKResult.Success(Unit)
        }
        val gate = CompletableDeferred<CameraKResult<Unit>>()
        pendingStart = gate
        try {
            return gate.await()
        } catch (e: CancellationException) {
            // A cancelled start leaves the camera closed; an open that arrives later finds no start to complete.
            if (pendingStart === gate) {
                pendingStart = null
                _state.value = EngineState.Closed
            }
            throw e
        }
    }

    private fun failStart(error: CameraKError): CameraKResult<Unit> {
        _state.value = EngineState.Failed(error)
        return CameraKResult.Failure(error)
    }

    override suspend fun stop() {
        callLog += "stop"
        if (closed) return
        completePendingStart(CameraKResult.Failure(CameraKError.Closed))
        recording?.let { endForShutdown(it).await() }
        _state.value = EngineState.Closed
    }

    override fun close() {
        callLog += "close"
        if (closed) return
        closed = true
        completePendingStart(CameraKResult.Failure(CameraKError.Closed))
        heldCapture?.complete(Unit)
        _state.value = EngineState.Closed
        val active = recording
        if (active == null) release() else endForShutdown(active).invokeOnCompletion { release() }
    }

    override suspend fun closeAndJoin() {
        close()
        released.await()
    }

    private fun release() {
        if (released.isCompleted) return
        releaseCount++
        released.complete(Unit)
    }

    override suspend fun capture(): CameraKResult<CapturedImage> {
        callLog += "capture"
        refusal()?.let { return it }
        if (holdNext) {
            holdNext = false
            val gate = CompletableDeferred<Unit>()
            heldCapture = gate
            try {
                gate.await()
            } finally {
                heldCapture = null
            }
            if (closed) return CameraKResult.Failure(CameraKError.Closed)
        }
        return captureResult()
    }

    /**
     * Clamps [ratio] into the bound camera's zoom range, sends the clamped value to the fake platform
     * and returns it, as every engine does: a pinch overshoots its ends, and a clamp is what it
     * expects. A camera with a fixed zoom takes only its one ratio and refuses any other with
     * `Unsupported(ZOOM)`. The [zoom] flow still changes only through [reportZoom].
     */
    override suspend fun setZoom(ratio: Float): CameraKResult<ZoomState> {
        callLog += "setZoom"
        refusal()?.let { return it }
        val camera = selectedCamera ?: return CameraKResult.Failure(CameraKError.NotReady)
        val applied = when {
            camera.minZoom < camera.maxZoom -> ratio.coerceIn(camera.minZoom, camera.maxZoom)
            ratio == camera.minZoom -> ratio
            else -> return CameraKResult.Failure(CameraKError.Unsupported(CameraFeature.ZOOM))
        }
        appliedZoomRatio = applied
        updateDesired(zoomRatio = applied)
        return CameraKResult.Success(ZoomState(applied, camera.minZoom, camera.maxZoom))
    }

    override suspend fun setFlashMode(mode: FlashMode): CameraKResult<FlashMode> {
        callLog += "setFlashMode"
        refusal()?.let { return it }
        val camera = selectedCamera ?: return CameraKResult.Failure(CameraKError.NotReady)
        if (mode !in _capabilities.value.supportedFlashModes(camera.id)) {
            val feature = if (mode == FlashMode.AUTO) CameraFeature.FLASH_AUTO else CameraFeature.FLASH_ON
            return CameraKResult.Failure(CameraKError.Unsupported(feature))
        }
        appliedFlashMode = mode
        updateDesired(flashMode = mode)
        return CameraKResult.Success(mode)
    }

    override suspend fun setTorchMode(mode: TorchMode): CameraKResult<TorchMode> {
        callLog += "setTorchMode"
        refusal()?.let { return it }
        val camera = selectedCamera ?: return CameraKResult.Failure(CameraKError.NotReady)
        if (mode !in _capabilities.value.supportedTorchModes(camera.id)) {
            return CameraKResult.Failure(CameraKError.Unsupported(CameraFeature.TORCH))
        }
        appliedTorchMode = mode
        updateDesired(torchMode = mode)
        return CameraKResult.Success(mode)
    }

    override suspend fun selectCamera(selection: CameraSelection): CameraKResult<LensInfo> {
        callLog += "selectCamera"
        if (closed) return CameraKResult.Failure(CameraKError.Closed)
        if (recording != null) return CameraKResult.Failure(CameraKError.Busy(CameraOperation.LENS))
        val camera = resolve(selection) ?: return CameraKResult.Failure(CameraKError.NoCamera(lensOf(selection)))
        selectedCamera = camera
        updateDesired(selection = selection)
        if (_state.value == EngineState.Open || _state.value is EngineState.Interrupted) applyDesired()
        return CameraKResult.Success(camera)
    }

    override suspend fun setTargetOrientation(orientation: DeviceOrientation?): CameraKResult<Unit> {
        callLog += "setTargetOrientation"
        if (closed) return CameraKResult.Failure(CameraKError.Closed)
        updateDesired(targetOrientation = orientation)
        return CameraKResult.Success(Unit)
    }

    override suspend fun prepareRecording(config: VideoConfiguration): CameraKResult<Unit> {
        callLog += "prepareRecording"
        refusal()?.let { return it }
        if (recording != null) return CameraKResult.Failure(CameraKError.Busy(CameraOperation.RECORD_PREPARE))
        return CameraKResult.Success(Unit)
    }

    override suspend fun startRecording(config: VideoConfiguration): CameraKResult<String> {
        callLog += "startRecording"
        refusal()?.let { return it }
        if (recording != null) return CameraKResult.Failure(CameraKError.Busy(CameraOperation.RECORD_START))
        recordingCount++
        val directory = config.outputDirectory ?: "/fake"
        val active = ActiveRecording(
            path = "$directory/${config.filePrefix}_${recordingCount.toString().padStart(4, '0')}.mp4",
            hasAudio = config.enableAudio,
        )
        recording = active
        if (autoStartRecording) markStarted(active)
        return active.started.await()
    }

    override suspend fun stopRecording(): CameraKResult<RecordedVideo> {
        callLog += "stopRecording"
        if (closed) return CameraKResult.Failure(CameraKError.Closed)
        val active = recording?.takeIf { it.started.isCompleted }
            ?: return CameraKResult.Failure(CameraKError.InvalidState(CameraOperation.RECORD_STOP))
        if (active.requestedEnd == null) active.requestedEnd = RecordingEndReason.USER
        if (autoFinalizeRecording) finalize(active, CameraKResult.Success(videoOf(active)), RecordingEndReason.USER)
        return active.finalized.await()
    }

    override suspend fun pauseRecording(): CameraKResult<Unit> {
        callLog += "pauseRecording"
        return pauseOrResume(CameraOperation.RECORD_PAUSE, EngineRecordingEvent.Paused)
    }

    override suspend fun resumeRecording(): CameraKResult<Unit> {
        callLog += "resumeRecording"
        return pauseOrResume(CameraOperation.RECORD_RESUME, EngineRecordingEvent.Resumed)
    }

    private fun pauseOrResume(operation: CameraOperation, event: EngineRecordingEvent): CameraKResult<Unit> {
        if (closed) return CameraKResult.Failure(CameraKError.Closed)
        if (recording?.started?.isCompleted != true) return CameraKResult.Failure(CameraKError.InvalidState(operation))
        if (!supportsPause) return CameraKResult.Failure(CameraKError.Unsupported(CameraFeature.PAUSE_RESUME))
        _recordingEvents.tryEmit(event)
        return CameraKResult.Success(Unit)
    }

    /** The platform confirms the held start. */
    public fun deliverOpen() {
        callback("deliverOpen") {
            val gate = pendingStart ?: return@callback
            pendingStart = null
            applyDesired()
            _state.value = EngineState.Open
            gate.complete(CameraKResult.Success(Unit))
        }
    }

    /** Publishes `Failed(error)`, then the held start, or the next one, returns `Failure(error)`. */
    public fun failOpen(error: CameraKError) {
        callback("failOpen") {
            val gate = pendingStart
            if (gate == null) {
                if (!isLive()) pendingOpenFailure = error
                return@callback
            }
            pendingStart = null
            gate.complete(failStart(error))
        }
    }

    /** The next start returns `Failure(error)` without publishing Failed: a contract breach the holder must survive. */
    public fun startReturnsWithoutPublishing(error: CameraKError) {
        unpublishedStartFailure = error
    }

    /**
     * While open, the platform holds the session. During a held start, the start succeeds with
     * Interrupted. While Closed it is dropped: it belongs to no live generation.
     */
    public fun interrupt(reason: InterruptionReason) {
        callback("interrupt") {
            val gate = pendingStart
            when {
                gate != null -> {
                    pendingStart = null
                    applyDesired()
                    _state.value = EngineState.Interrupted(reason)
                    gate.complete(CameraKResult.Success(Unit))
                }
                _state.value == EngineState.Open -> _state.value = EngineState.Interrupted(reason)
            }
        }
    }

    public fun endInterruption() {
        callback("endInterruption") {
            if (_state.value is EngineState.Interrupted) _state.value = EngineState.Open
        }
    }

    /** A runtime failure while open: publishes `Failed(error)`, which ends the generation, and ends any recording. */
    public fun fail(error: CameraKError) {
        callback("fail") {
            if (!isLive()) return@callback
            _state.value = EngineState.Failed(error)
            recording?.let { finalize(it, CameraKResult.Failure(error), RecordingEndReason.ERROR) }
        }
    }

    /** Publishes Open after a runtime failure, as a platform that reopens on its own would: a contract breach the holder must survive. */
    public fun reopenAfterFailure() {
        if (!closed && _state.value is EngineState.Failed) _state.value = EngineState.Open
    }

    /** The next capture waits until [releaseCapture] or close. */
    public fun holdNextCapture() {
        holdNext = true
    }

    public fun releaseCapture() {
        heldCapture?.complete(Unit)
    }

    /** The platform ends the recording on its own (a limit, an error, an interruption). */
    public fun finishRecording(result: CameraKResult<RecordedVideo>, reason: RecordingEndReason) {
        callback("finishRecording") {
            val active = recording ?: return@callback
            if (!active.started.isCompleted) active.started.complete(result.map { active.path })
            finalize(active, result, reason)
        }
    }

    /** The platform confirms the held recording start. */
    public fun deliverRecordingStarted() {
        callback("deliverRecordingStarted") {
            recording?.takeIf { !it.started.isCompleted }?.let(::markStarted)
        }
    }

    /** The platform finalizes the recording whose end was requested. */
    public fun deliverRecordingFinalized() {
        // Not gated on close: close requested this finalization.
        callback("deliverRecordingFinalized", afterClose = true) {
            val active = recording ?: return@callback
            val reason = active.requestedEnd ?: return@callback
            finalize(active, CameraKResult.Success(videoOf(active)), reason)
        }
    }

    /** The next scripted platform callback throws inside the fake's callback boundary, which logs it; it has no effect. */
    public fun throwFromNextCallback() {
        throwFromNext = true
    }

    public fun reportFlash(mode: FlashMode) {
        callback("reportFlash") {
            if (isLive()) _hardware.value = _hardware.value.with(flashMode = mode)
        }
    }

    public fun reportTorch(mode: TorchMode) {
        callback("reportTorch") {
            if (isLive()) _hardware.value = _hardware.value.with(torchMode = mode)
        }
    }

    public fun reportZoom(ratio: Float, minRatio: Float = 1f, maxRatio: Float = 10f) {
        callback("reportZoom") {
            if (isLive()) _zoom.value = ZoomState(ratio, minRatio, maxRatio)
        }
    }

    /** [lensId] is a `LensInfo.id` from the capabilities; an unknown one is ignored. */
    public fun reportCamera(lensId: String) {
        callback("reportCamera") {
            val camera = _capabilities.value.allLenses.firstOrNull { it.id == lensId } ?: return@callback
            if (isLive()) _hardware.value = _hardware.value.with(camera = camera)
        }
    }

    // The fake's platform callback boundary: a callback after close does nothing, and one that throws is logged and dropped.
    private inline fun callback(name: String, afterClose: Boolean = false, block: () -> Unit) {
        if (closed && !afterClose) return
        try {
            if (throwFromNext) {
                throwFromNext = false
                throw ScriptedCallbackFailure(name)
            }
            block()
        } catch (e: ScriptedCallbackFailure) {
            CameraKLogger.w(TAG, "A fake platform callback threw; it had no effect", e)
        }
    }

    private class ScriptedCallbackFailure(callback: String) : IllegalStateException("scripted failure in $callback")

    // A live session: platform reports reach the engine only between an accepted start and the end of its generation.
    private fun isLive(): Boolean {
        val current = _state.value
        return !closed && (current == EngineState.Open || current is EngineState.Interrupted)
    }

    // Commands need an open camera, as on a platform; after close every call returns Closed.
    private fun refusal(): CameraKResult.Failure? = when {
        closed -> CameraKResult.Failure(CameraKError.Closed)
        _state.value != EngineState.Open -> CameraKResult.Failure(CameraKError.NotReady)
        else -> null
    }

    private fun completePendingStart(result: CameraKResult<Unit>) {
        val gate = pendingStart ?: return
        pendingStart = null
        gate.complete(result)
    }

    // A bind: the platform receives the desired hardware, and its observer reports the bound camera.
    private fun applyDesired() {
        val wanted = desired ?: return
        appliedFlashMode = wanted.flashMode
        appliedTorchMode = wanted.torchMode
        appliedZoomRatio = wanted.zoomRatio
        appliedCameraId = selectedCamera?.id
        _hardware.value = _hardware.value.with(camera = selectedCamera)
    }

    private fun updateDesired(
        selection: CameraSelection? = null,
        flashMode: FlashMode? = null,
        torchMode: TorchMode? = null,
        zoomRatio: Float? = null,
        targetOrientation: DeviceOrientation? = desired?.targetOrientation,
    ) {
        val current = desired ?: return
        desired = DesiredHardware(
            selection = selection ?: current.selection,
            flashMode = flashMode ?: current.flashMode,
            torchMode = torchMode ?: current.torchMode,
            zoomRatio = zoomRatio ?: current.zoomRatio,
            targetOrientation = targetOrientation,
        )
    }

    private fun unsupportedFeature(): CameraFeature? {
        val offered = _capabilities.value
        return when {
            configuration.imageFormat !in offered.supportedImageFormats -> CameraFeature.IMAGE_FORMAT
            configuration.analysisFormat !in offered.supportedAnalysisFormats -> CameraFeature.ANALYSIS_FORMAT
            configuration.aspectRatio !in offered.supportedAspectRatios -> CameraFeature.ASPECT_RATIO
            else -> null
        }
    }

    private fun resolve(selection: CameraSelection): LensInfo? {
        val offered = _capabilities.value
        return when (selection) {
            is CameraSelection.ById -> offered.allLenses.firstOrNull { it.id == selection.id }
            is CameraSelection.ByLens -> {
                val facing = offered.lenses(selection.lens)
                facing.firstOrNull {
                    selection.deviceType == CameraDeviceType.DEFAULT ||
                        it.deviceType == selection.deviceType
                }
                    ?: facing.firstOrNull()
            }
        }
    }

    private fun lensOf(selection: CameraSelection): CameraLens? = (selection as? CameraSelection.ByLens)?.lens

    private fun markStarted(active: ActiveRecording) {
        _recordingEvents.tryEmit(EngineRecordingEvent.Started(active.path, active.hasAudio))
        active.started.complete(CameraKResult.Success(active.path))
    }

    // Rule 7: stop and close finalize an active recording before the camera closes.
    private fun endForShutdown(active: ActiveRecording): CompletableDeferred<CameraKResult<RecordedVideo>> {
        if (!active.started.isCompleted) {
            active.started.complete(CameraKResult.Failure(CameraKError.Closed))
            recording = null
            active.finalized.complete(CameraKResult.Failure(CameraKError.Closed))
            return active.finalized
        }
        if (active.requestedEnd == null) active.requestedEnd = RecordingEndReason.SHUTDOWN
        if (autoFinalizeRecording) finalize(active, CameraKResult.Success(videoOf(active)), RecordingEndReason.SHUTDOWN)
        return active.finalized
    }

    private fun finalize(active: ActiveRecording, result: CameraKResult<RecordedVideo>, reason: RecordingEndReason) {
        if (active.finalized.isCompleted) return
        recording = null
        _recordingEvents.tryEmit(EngineRecordingEvent.Finalized(result, reason))
        active.finalized.complete(result)
    }

    private fun videoOf(active: ActiveRecording): RecordedVideo =
        FakeCaptures.video(path = active.path, hasAudio = active.hasAudio)

    private class ActiveRecording(val path: String, val hasAudio: Boolean) {
        val started = CompletableDeferred<CameraKResult<String>>()
        val finalized = CompletableDeferred<CameraKResult<RecordedVideo>>()
        var requestedEnd: RecordingEndReason? = null
    }

    private companion object {
        const val TAG = "CameraK"
        const val EVENT_BUFFER = 64
    }
}

private fun HardwareState.with(
    flashMode: FlashMode = this.flashMode,
    torchMode: TorchMode = this.torchMode,
    camera: LensInfo? = this.camera,
): HardwareState = HardwareState(flashMode, torchMode, camera, targetOrientation)

private fun CameraCapabilities.withPauseResume(supported: Boolean): CameraCapabilities = CameraCapabilities(
    allLenses = allLenses,
    flashModes = allLenses.associate { it.id to supportedFlashModes(it.id) },
    torchModes = allLenses.associate { it.id to supportedTorchModes(it.id) },
    supportsPauseResume = supported,
    supportedVideoQualities = supportedVideoQualities,
    supportsVideoBitrate = supportsVideoBitrate,
    supportedAspectRatios = supportedAspectRatios,
    supportedImageFormats = supportedImageFormats,
    supportedAnalysisFormats = supportedAnalysisFormats,
    supportsSquareCapture = supportsSquareCapture,
)

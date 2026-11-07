@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.state

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.controls.NormalizedPoint
import com.kashif.cameraK.coroutines.isFatal
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.CameraEngineFactory
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.HardwareState
import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.engine.createPlatformEngine
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.permissions.platformPermissionChecker
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.plugin.PluginHost
import com.kashif.cameraK.plugin.effectiveConfig
import com.kashif.cameraK.recording.RecordingController
import com.kashif.cameraK.recording.RecordingState
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.result.onSuccess
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.utils.InternalCameraKApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * The camera's state and lifecycle, free of Compose. It owns its coroutine scope and one camera
 * engine, and reports the camera's lifecycle as [cameraState], driven by what the platform reports.
 *
 * Call [start] when the camera should run (the host reached STARTED) and [stop] when it should not;
 * both return at once and may be called from any thread. [close] releases everything, once; after
 * it the holder is [CameraKState.Closed] for good. In Compose, `rememberCameraKStateHolder` does all
 * three.
 *
 * Before opening, the holder checks the camera permission and publishes `Error(Permission)` if it
 * is not granted. While it shows that error, [recheckPermission] starts the camera once the
 * permission is granted, without a [retry].
 *
 * @sample com.kashif.cameraK.samples.cameraKStateHolderSample
 */
public class CameraKStateHolder @CameraKEngineApi constructor(
    config: CameraConfiguration,
    engineFactory: CameraEngineFactory,
    plugins: List<CameraKPlugin> = emptyList(),
    environment: CameraKEnvironment = CameraKEnvironment(),
) : AutoCloseable {
    /** The production constructor: the platform's engine and the platform's permission checker. */
    public constructor(
        config: CameraConfiguration,
        platformContext: CameraKPlatformContext,
        plugins: List<CameraKPlugin> = emptyList(),
    ) : this(
        config,
        CameraEngineFactory { c, env -> createPlatformEngine(c, platformContext, env) },
        plugins,
        productionEnvironment(platformContext),
    )

    /**
     * For `:cameraK`'s retained holder: the engine constructor, whose first start waits for
     * [startAfter], the old holder's [closeCompletion], at most ten seconds. Public only because
     * `:cameraK` is another module.
     */
    @InternalCameraKApi
    @CameraKEngineApi
    public constructor(
        config: CameraConfiguration,
        engineFactory: CameraEngineFactory,
        plugins: List<CameraKPlugin>,
        environment: CameraKEnvironment,
        startAfter: Job?,
    ) : this(config, engineFactory, plugins, environment) {
        previousClose.value = startAfter
    }

    /** For `:cameraK`'s retained holder: the production constructor, starting after [startAfter]. */
    @InternalCameraKApi
    public constructor(
        config: CameraConfiguration,
        platformContext: CameraKPlatformContext,
        plugins: List<CameraKPlugin>,
        startAfter: Job?,
    ) : this(config, platformContext, plugins) {
        previousClose.value = startAfter
    }

    // The holder's one scope. The handler logs what nothing else caught and, during a session, ends
    // it in Error(Internal) rather than letting it reach the platform's uncaught-exception handler.
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob(environment.parentJob) + environment.dispatchers.default + CoroutineName("CameraK") +
            CoroutineExceptionHandler { _, e ->
                CameraKLogger.e(TAG, "A camera coroutine failed", e)
                lifecycle.failSession(e)
            },
    )

    // Created eagerly; its scope is a child of the holder's.
    private val engine: CameraEngine =
        engineFactory.create(effectiveConfig(config, plugins), environment.under(scope.coroutineContext.job))

    private val desiredHardware = MutableStateFlow(config.toDesiredHardware())

    private val closing = atomic(false)

    // Completed when the engine's closeAndJoin returns or throws; never when it outlives the bound.
    private val engineClosed = Job()

    /**
     * Completes once the engine has closed after [close], so a holder built to replace this one can
     * start its camera after this one has let it go. It stays incomplete when the engine does not
     * close within the bound [close] waits. Public only because `:cameraK` is another module.
     */
    @InternalCameraKApi
    public val closeCompletion: Job
        get() = engineClosed

    // The closeCompletion of the holder this one replaces, until the first start has waited for it.
    private val previousClose = atomic<Job?>(null)

    private val _events = MutableSharedFlow<CameraKEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Unsolicited notices, such as a plugin that failed. Lossy and not replayed: collect them to see
     * them. A command's outcome is its result, never an event.
     */
    public val events: SharedFlow<CameraKEvent> = _events.asSharedFlow()

    // Captures in flight.
    private val pendingCaptures = MutableStateFlow(0)

    private val _captures = MutableSharedFlow<CapturedImage>(
        extraBufferCapacity = CAPTURE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Every successful capture, once its file is written, including captures whose caller was
     * cancelled. Not replayed, buffered (64, the oldest dropped first); collect it from any thread.
     */
    public val captures: SharedFlow<CapturedImage> = _captures.asSharedFlow()

    /** The zoom the platform reports, at gesture rate. */
    public val zoom: StateFlow<ZoomState> = engine.zoom

    /**
     * What the cameras offer, from the engine: `CameraCapabilities.EMPTY` until it has enumerated
     * them. [com.kashif.cameraK.controls.cycleFlashMode] reads the current camera's flash modes here.
     */
    public val capabilities: StateFlow<CameraCapabilities> = engine.capabilities

    /**
     * The camera's one source of analysis frames, for an app's own analysis: frames reach an
     * analyzer only while the camera is Ready. Plugins get the same source from their context.
     */
    public val frames: FrameSource
        get() = engine.frames

    private val lifecycle = SessionLifecycle(
        engine = engine,
        permissionChecker = environment.permissionChecker,
        scope = scope,
        desiredHardware = { desiredHardware.value },
        beforeReady = ::beforeReady,
        awaitPreviousClose = ::awaitPreviousClose,
        hostStopGrace = { recording.awaitHostStopGrace() },
        hostStop = { recording.stopForHostStop() },
    )

    /**
     * The camera's lifecycle: [CameraKState.Idle] until [start], then Starting, and Ready once the
     * platform confirms the camera is open. [CameraKState.Closed] after [close].
     */
    public val cameraState: StateFlow<CameraKState> = lifecycle.state

    private val commands = CommandRunner(scope) {
        when {
            closing.value -> CameraKError.Closed
            cameraState.value !is CameraKState.Ready -> CameraKError.NotReady
            else -> null
        }
    }

    private val recording = RecordingController(
        engine = engine,
        commands = commands,
        scope = scope,
        timeSource = environment.timeSource,
        permissionChecker = environment.permissionChecker,
        emit = { _events.tryEmit(it) },
    )

    private val pluginHost = PluginHost(
        holder = this,
        holderScope = scope,
        dispatchers = environment.dispatchers,
        frames = engine.frames,
        recordingState = recording.state,
    ) { _events.tryEmit(it) }

    /**
     * True while the holder asks for the platform default camera: the configuration names no lens and
     * no camera id, and no camera was selected since. `:cameraK` saves no lens while it is true, so a
     * restored configuration keeps meaning the platform default (§6.1 "Default lens").
     */
    @InternalCameraKApi
    public val selectsPlatformDefault: StateFlow<Boolean> = DerivedStateFlow(
        read = { desiredHardware.value.selection is CameraSelection.Default },
        changes = desiredHardware.map { it.selection is CameraSelection.Default },
    )

    /**
     * The camera's properties, derived from what the engine reports and the captures in flight.
     * Nothing else writes it: a command changes it only once the platform reports the change.
     * [CameraUIState.flashMode], [CameraUIState.torchMode] and the camera fields are the platform's
     * from the first bind on.
     */
    public val uiState: StateFlow<CameraUIState> = DerivedStateFlow(
        read = { uiStateOf(engine.hardware.value, pendingCaptures.value) },
        changes = combine(engine.hardware, pendingCaptures, ::uiStateOf),
    )

    private val zoomRequests = LatestWins<Float, CameraKResult<ZoomState>>(
        launch = { commands.launch(it) },
        call = { ratio ->
            commands.execute(CameraOperation.ZOOM) { engine.setZoom(ratio) }
                .onSuccess { applied -> desire { it.with(zoomRatio = applied.ratio) } }
        },
        abandoned = { CameraKResult.Failure(CameraKError.Closed) },
    )

    private val orientationRequests = LatestWins<DeviceOrientation?, CameraKResult<Unit>>(
        launch = { commands.launch(it) },
        call = { orientation ->
            commands.execute(CameraOperation.ORIENTATION) { engine.setTargetOrientation(orientation) }
                .onSuccess { desire { it.with(targetOrientation = orientation) } }
        },
        abandoned = { CameraKResult.Failure(CameraKError.Closed) },
    )

    /** The device's physical orientation, as the engine reports it. */
    public val deviceOrientation: StateFlow<DeviceOrientation> = engine.deviceOrientation

    /**
     * The engine's preview, for `:cameraK`'s `CameraPreview(holder)` to attach to. Public only because
     * `:cameraK` is another module.
     */
    @InternalCameraKApi
    @CameraKEngineApi
    public val previewSource: PlatformPreviewSource
        get() = engine.preview

    init {
        plugins.forEach(pluginHost::add)
    }

    // ═══════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════

    /** Opens the camera from Idle, or again from Error; does nothing while it is starting or open. */
    public fun start() {
        lifecycle.start()
    }

    /**
     * Closes the camera and returns to Idle. A start in progress is cancelled and publishes no error.
     *
     * While a recording is live the camera first stays open, and Ready, for two seconds: a [start] in
     * that window, such as the one an Activity recreation (a rotation) makes, carries on with the same
     * recording. Otherwise the recording then ends with [CameraKEvent.RecordingEnded] (INTERRUPTED)
     * and the camera closes.
     */
    public fun stop() {
        lifecycle.stop()
    }

    /** Starts again from [CameraKState.Error]. Returns false, doing nothing, in any other state. */
    public fun retry(): Boolean = !closing.value && lifecycle.retry()

    /**
     * From `Error(Permission)` whose status is not Restricted, starts the camera once the permission
     * now allows opening. Does nothing in any other state. Call it when the host resumes; the
     * default Compose path does.
     */
    public fun recheckPermission() {
        lifecycle.recheckPermission()
    }

    /**
     * Releases the camera, once: publishes [CameraKState.Closed], ends a recording while the camera is
     * still open ([CameraKEvent.RecordingEnded] with SHUTDOWN; a start still in flight returns
     * `Failure(Closed)`), detaches the plugins, lets running commands finish for at most
     * [COMMAND_DRAIN_TIMEOUT], then closes the engine, which completes the rest with
     * `Failure(Closed)`, waiting at most a bounded time for it. From here every command returns
     * `Failure(Closed)`. Returns at once; [closeAndJoin] waits.
     */
    override fun close() {
        if (!closing.compareAndSet(expect = false, update = true)) return
        lifecycle.enterClosed()
        scope.launch(start = CoroutineStart.ATOMIC) {
            // The scope ends whatever a step does, so closeAndJoin returns and no coroutine outlives the holder.
            try {
                withContext(NonCancellable) {
                    // Every platform ends a recording as a failure once its camera closes underneath it.
                    recording.stopForShutdown()
                    lifecycle.shutDown()
                    pluginHost.detachAll()
                    // Commands finishing normally land (a capture writing its file); one waiting on a
                    // callback that will not come does not hold the engine open beyond the bound.
                    commands.drain(COMMAND_DRAIN_TIMEOUT)
                    closeEngine()
                }
            } finally {
                scope.cancel()
            }
        }
    }

    // Bounded, and never thrown out of the release: an engine that breaks its contract by throwing
    // from closeAndJoin is logged, and the holder closes without it. The commands still running
    // return promptly once the engine has closed: their engine calls answer Closed.
    private suspend fun closeEngine() {
        val closed = try {
            withTimeoutOrNull(ENGINE_CLOSE_TIMEOUT) {
                engine.closeAndJoin()
                engineClosed.complete()
                commands.joinAll()
            }
        } catch (e: Throwable) {
            if (e.isFatal()) throw e
            CameraKLogger.e(TAG, "Closing the camera engine failed; the holder closed without it", e)
            // The close ended, by throwing: nothing is left to wait for.
            engineClosed.complete()
            return
        }
        if (closed == null) {
            CameraKLogger.e(
                TAG,
                "Closing the camera engine timed out; the holder closed without waiting for it",
                EngineCloseTimeoutException(ENGINE_CLOSE_TIMEOUT),
            )
        }
    }

    /**
     * [close], then waits until the release has finished and every coroutine the holder started has
     * ended. Do not call it from a coroutine the holder runs, such as one in a plugin's scope.
     */
    public suspend fun closeAndJoin() {
        close()
        scope.coroutineContext.job.join()
    }

    // A holder built to replace another opens the camera only once the old engine has let it go, or
    // after PREVIOUS_CLOSE_TIMEOUT: an engine that never closes cannot keep this one from starting.
    private suspend fun awaitPreviousClose() {
        val previous = previousClose.value ?: return
        if (withTimeoutOrNull(PREVIOUS_CLOSE_TIMEOUT) { previous.join() } == null) {
            CameraKLogger.w(
                TAG,
                "The camera this holder replaces did not close within $PREVIOUS_CLOSE_TIMEOUT; starting anyway",
            )
        }
        previousClose.value = null
    }

    /** Runs [block] in the holder's scope, which [close] cancels. For `bindToLifecycle`. */
    internal fun launchInHolderScope(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    private fun beforeReady() {
        if (closing.value) return
        pluginHost.attachPending()
    }

    // ═══════════════════════════════════════════════════════════════
    // Commands
    // ═══════════════════════════════════════════════════════════════
    //
    // Each command returns once the platform has applied the change, with the applied value. Outside
    // Ready it returns Failure(NotReady), after close Failure(Closed). A caller that is cancelled
    // stops waiting; the platform operation still finishes in the holder's scope.

    /**
     * Takes a picture to a file in app-owned storage. The image also reaches [captures], even when
     * the caller is cancelled. More than three captures at once return `Failure(Busy(CAPTURE))`.
     */
    public suspend fun capture(): CameraKResult<CapturedImage> {
        commands.refused()?.let { return it }
        if (!takeCaptureSlot()) return CameraKResult.Failure(CameraKError.Busy(CameraOperation.CAPTURE))
        val job = commands.start(CameraOperation.CAPTURE) {
            try {
                engine.capture().onSuccess { _captures.tryEmit(it) }
            } finally {
                pendingCaptures.update { it - 1 }
            }
        }
        return commands.await(job)
    }

    /**
     * Sets the zoom ratio. A ratio outside the camera's range is clamped into it, and the result is
     * the zoom the platform applied. Latest wins: a call replaced while it waits returns the
     * replacing call's result.
     */
    public suspend fun setZoom(ratio: Float): CameraKResult<ZoomState> {
        commands.refused()?.let { return it }
        return zoomRequests.submit(ratio)
    }

    /** Sets the flash mode for captures; `Unsupported` for a mode the current camera does not offer. */
    public suspend fun setFlashMode(mode: FlashMode): CameraKResult<FlashMode> =
        commands.run(CameraOperation.FLASH) { engine.setFlashMode(mode) }
            .onSuccess { applied -> desire { it.with(flashMode = applied) } }

    /** Sets the torch; `Unsupported` for a mode the current camera does not offer. */
    public suspend fun setTorchMode(mode: TorchMode): CameraKResult<TorchMode> =
        commands.run(CameraOperation.TORCH) { engine.setTorchMode(mode) }
            .onSuccess { applied -> desire { it.with(torchMode = applied) } }

    /**
     * Switches to the camera facing [lens] of [deviceType], in one rebind, and returns the camera
     * that is live afterwards. `NoCamera` leaves the current camera in place. While a recording is
     * live it is `Failure(Busy(LENS))`, or `Failure(Busy(DEVICE_TYPE))` when only the device type
     * would change, and nothing changes.
     */
    public suspend fun selectCamera(
        lens: CameraLens,
        deviceType: CameraDeviceType = CameraDeviceType.DEFAULT,
    ): CameraKResult<LensInfo> = select(CameraSelection.ByLens(lens, deviceType))

    /**
     * Switches to the camera with this `LensInfo.id` from [capabilities]: a desktop device, an
     * Android camera id or an iOS uniqueID. `NoCamera` for an unknown id leaves the current camera in
     * place.
     */
    public suspend fun selectCamera(id: String): CameraKResult<LensInfo> = select(CameraSelection.ById(id))

    private suspend fun select(selection: CameraSelection): CameraKResult<LensInfo> {
        commands.refused()?.let { return it }
        if (recording.isLive()) return CameraKResult.Failure(CameraKError.Busy(switchOperation(selection)))
        return commands.run(CameraOperation.LENS) { engine.selectCamera(selection) }
            .onSuccess { desire { it.with(selection = selection) } }
    }

    // Refused while recording: DEVICE_TYPE when the switch keeps the lens and changes only the device type.
    private fun switchOperation(selection: CameraSelection): CameraOperation {
        val current = engine.hardware.value.camera ?: return CameraOperation.LENS
        val target = when (selection) {
            is CameraSelection.ByLens -> selection.lens
            is CameraSelection.ById -> capabilities.value.allLenses.firstOrNull { it.id == selection.id }?.lens
            is CameraSelection.Default -> null
        }
        return if (target == current.lens) CameraOperation.DEVICE_TYPE else CameraOperation.LENS
    }

    /**
     * Fixes the orientation of captures and recordings; null follows the device. Latest wins, as for
     * [setZoom].
     */
    public suspend fun setTargetOrientation(orientation: DeviceOrientation?): CameraKResult<Unit> {
        commands.refused()?.let { return it }
        return orientationRequests.submit(orientation)
    }

    /**
     * Meters focus and exposure at [point] of the preview's content rectangle; the platform returns
     * to its own metering after its default timeout, and a camera switch drops the point.
     * `Failure(InvalidArgument(FOCUS_POINT))` for a coordinate outside [0, 1] or NaN, and
     * `Failure(Unsupported(FOCUS_POINT))` on a camera whose capabilities do not list it in
     * `supportsFocusPoint`.
     */
    public suspend fun focusAt(point: NormalizedPoint): CameraKResult<Unit> = TODO("Y-focus-api")

    /**
     * Sets the exposure compensation [index], in the bound camera's
     * [CameraCapabilities.exposureCompensationRange] (each step is its `exposureCompensationStep` EV).
     * `Failure(Unsupported(EXPOSURE_COMPENSATION))` on a camera without one, and
     * `Failure(InvalidArgument(EXPOSURE_COMPENSATION))` for an index outside it. The index is kept:
     * after a camera switch or a restart it is applied again, clamped into the new camera's range.
     * [uiState] shows it once the platform has applied it.
     */
    public suspend fun setExposureCompensation(index: Int): CameraKResult<Unit> = TODO("Y-focus-api")

    /**
     * Returns the camera to the platform's metering and exposure index 0, for what it supports;
     * `Failure(Unsupported(FOCUS_POINT))` on a camera that supports neither.
     */
    public suspend fun resetFocusAndExposure(): CameraKResult<Unit> = TODO("Y-focus-api")

    /**
     * Where the recording is: Idle, then Starting, Recording, Paused and Stopping, and Finished after
     * any end, with the outcome. The holder owns it;
     * [RecordingState.elapsed][com.kashif.cameraK.recording.elapsed] reads the recorded time.
     */
    public val recordingState: StateFlow<RecordingState>
        get() = recording.state

    /**
     * Adds the recording outputs ahead of time, so [startRecording] commits no configuration change.
     * `Failure(Busy(RECORD_PREPARE))` while a recording is live.
     */
    public suspend fun prepareRecording(config: VideoConfiguration = VideoConfiguration()): CameraKResult<Unit> {
        commands.refused()?.let { return it }
        return recording.prepare(config)
    }

    /**
     * Starts a recording and returns it once the platform confirms it started. A second start while
     * one is live is `Failure(Busy(RECORD_START))`; with [VideoConfiguration.enableAudio] and no
     * microphone permission it is `Failure(Permission(MICROPHONE, status))`, and nothing records; a
     * start the platform does not confirm within five seconds is `Failure(Timeout(RECORD_START))`.
     * Limits end the recording on the platform and arrive as [CameraKEvent.RecordingEnded].
     */
    public suspend fun startRecording(
        config: VideoConfiguration = VideoConfiguration(),
    ): CameraKResult<RecordingState.Recording> {
        commands.refused()?.let { return it }
        return recording.start(config)
    }

    /**
     * Stops the recording and returns the finished file. A stop while a start is resolving stops once
     * it has; every stop while one is in flight returns the same outcome. `Failure(InvalidState)`
     * when nothing is recording. A stop the platform does not finalize within five seconds returns
     * `Failure(Timeout(RECORD_STOP))`; the recording is then Finished with that failure and reason
     * ERROR, and [CameraKEvent.RecordingEnded] reports it.
     *
     * Unlike the other commands it also runs while the camera is [CameraKState.Interrupted]: the
     * platform can still finalize the file, and a refusal would leave a recording the app cannot end.
     */
    public suspend fun stopRecording(): CameraKResult<RecordedVideo> {
        stopRefusal()?.let { return it }
        return recording.stop()
    }

    // A stop runs in Ready and Interrupted; every other command only in Ready.
    private fun stopRefusal(): CameraKResult.Failure? = when {
        closing.value -> CameraKResult.Failure(CameraKError.Closed)
        cameraState.value is CameraKState.Interrupted -> null
        else -> commands.refused()
    }

    /**
     * Pauses the recording; `Failure(Unsupported(PAUSE_RESUME))` where the platform cannot, and
     * `Failure(NotReady)` while the camera is Interrupted.
     */
    public suspend fun pauseRecording(): CameraKResult<Unit> {
        commands.refused()?.let { return it }
        return recording.pause()
    }

    /** Resumes a paused recording; `Failure(NotReady)` while the camera is Interrupted. */
    public suspend fun resumeRecording(): CameraKResult<Unit> {
        commands.refused()?.let { return it }
        return recording.resume()
    }

    // Takes one of the in-flight capture slots, or none when all are taken.
    private fun takeCaptureSlot(): Boolean {
        var taken = false
        pendingCaptures.update { count ->
            taken = count < MAX_IN_FLIGHT_CAPTURES
            if (taken) count + 1 else count
        }
        return taken
    }

    // The desired hardware changes only through a successful command; the next start applies it.
    private fun desire(change: (DesiredHardware) -> DesiredHardware) {
        desiredHardware.update(change)
    }

    private fun uiStateOf(hardware: HardwareState, capturing: Int): CameraUIState = CameraUIState(
        flashMode = hardware.flashMode,
        torchMode = hardware.torchMode,
        cameraLens = hardware.camera?.lens,
        cameraDeviceType = hardware.camera?.deviceType ?: CameraDeviceType.DEFAULT,
        isCapturing = capturing > 0,
        pendingCaptures = capturing,
        cameraId = hardware.camera?.id,
        targetOrientation = hardware.targetOrientation,
    )

    // ═══════════════════════════════════════════════════════════════
    // Plugins
    // ═══════════════════════════════════════════════════════════════

    /**
     * Attaches [plugin]. Before the first session reaches Ready, it attaches then; after it, at once.
     * Attaching the same plugin twice does nothing.
     */
    public fun attachPlugin(plugin: CameraKPlugin) {
        pluginHost.add(plugin)
    }

    /** Detaches [plugin] if it is attached. */
    public fun detachPlugin(plugin: CameraKPlugin) {
        pluginHost.remove(plugin)
    }

    // ═══════════════════════════════════════════════════════════════
    // 1.x hints (design §10.4): each fails to compile with a ReplaceWith, and throws if reached
    // ═══════════════════════════════════════════════════════════════

    /** Removed in 2.0. */
    @Deprecated("Call close().", ReplaceWith("close()"), level = DeprecationLevel.ERROR)
    public fun shutdown(): Unit = throw removedIn2()

    /** Removed in 2.0. */
    @Deprecated("Read the holder's capabilities.", ReplaceWith("capabilities.value"), level = DeprecationLevel.ERROR)
    public fun getCameraCapabilities(): CameraCapabilities = throw removedIn2()

    /** Removed in 2.0. */
    @Deprecated(
        "Capture with the suspending capture(), which returns the image or why there is none.",
        ReplaceWith("capture()"),
        level = DeprecationLevel.ERROR,
    )
    public fun captureImage(): Unit = throw removedIn2()

    /** Removed in 2.0. */
    @Deprecated(
        "Cycle the flash with the suspending cycleFlashMode().",
        ReplaceWith("cycleFlashMode()", "com.kashif.cameraK.controls.cycleFlashMode"),
        level = DeprecationLevel.ERROR,
    )
    public fun toggleFlashMode(): Unit = throw removedIn2()

    /** Removed in 2.0. */
    @Deprecated(
        "Toggle the torch with the suspending toggleTorch().",
        ReplaceWith("toggleTorch()", "com.kashif.cameraK.controls.toggleTorch"),
        level = DeprecationLevel.ERROR,
    )
    public fun toggleTorchMode(): Unit = throw removedIn2()

    /** Removed in 2.0. */
    @Deprecated(
        "Switch cameras with the suspending toggleLens().",
        ReplaceWith("toggleLens()", "com.kashif.cameraK.controls.toggleLens"),
        level = DeprecationLevel.ERROR,
    )
    public fun toggleCameraLens(): Unit = throw removedIn2()

    private companion object {
        const val TAG = "CameraK"
        const val EVENT_BUFFER = 64
        const val CAPTURE_BUFFER = 64
    }
}

/** The production constructor's environment: the platform's permission checker. */
internal fun productionEnvironment(context: CameraKPlatformContext): CameraKEnvironment =
    CameraKEnvironment(permissionChecker = platformPermissionChecker(context))

/** What a 1.x hint throws when code compiled against 1.x reaches it. */
internal fun removedIn2(): UnsupportedOperationException =
    UnsupportedOperationException("Removed in CameraK 2.0; see the migration guide")

/** The engine did not close within [ENGINE_CLOSE_TIMEOUT]; logged, never thrown. */
internal class EngineCloseTimeoutException(timeout: Duration) :
    IllegalStateException("The camera engine did not close within $timeout")

// The environment the holder gives its engine: its own, with the holder's job as the parent.
private fun CameraKEnvironment.under(parent: Job): CameraKEnvironment =
    CameraKEnvironment(dispatchers, timeSource, parent, permissionChecker).also { it.runtime = runtime }

// A camera id wins over the lens; no lens is the platform default camera (§6.1 "Default lens").
private fun CameraConfiguration.toDesiredHardware(): DesiredHardware = DesiredHardware(
    selection = cameraId?.let { CameraSelection.ById(it) }
        ?: cameraLens?.let { CameraSelection.ByLens(it, cameraDeviceType) }
        ?: CameraSelection.Default(cameraDeviceType),
    flashMode = flashMode,
    torchMode = torchMode,
    zoomRatio = initialZoomRatio,
    targetOrientation = targetOrientation,
)

private fun DesiredHardware.with(
    selection: CameraSelection = this.selection,
    flashMode: FlashMode = this.flashMode,
    torchMode: TorchMode = this.torchMode,
    zoomRatio: Float = this.zoomRatio,
    targetOrientation: DeviceOrientation? = this.targetOrientation,
): DesiredHardware = DesiredHardware(selection, flashMode, torchMode, zoomRatio, targetOrientation)

/**
 * A state flow whose value is computed from other state flows on every read, so it is never stale,
 * not even after the scope that ran its collectors is gone. Collecting it collects [changes].
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class DerivedStateFlow<T>(private val read: () -> T, private val changes: Flow<T>) : StateFlow<T> {
    override val value: T get() = read()
    override val replayCache: List<T> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        changes.distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}

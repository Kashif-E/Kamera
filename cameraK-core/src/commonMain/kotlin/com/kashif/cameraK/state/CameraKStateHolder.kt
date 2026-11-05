@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.state

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.coroutines.isFatal
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.CameraEngineFactory
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.HardwareState
import com.kashif.cameraK.engine.LegacyControllerAccess
import com.kashif.cameraK.engine.LegacyControllerEngine
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.cancellation.CancellationException
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
 * @example
 * ```kotlin
 * val holder = CameraKStateHolder(CameraConfiguration(), CameraKPlatformContext(context))
 * holder.start()
 * holder.cameraState.collect { state ->
 *     when (state) {
 *         CameraKState.Idle, CameraKState.Starting -> showLoading()
 *         is CameraKState.Ready, is CameraKState.Interrupted -> showCamera()
 *         is CameraKState.Error -> showError(state.error)
 *         CameraKState.Closed -> Unit
 *     }
 * }
 * // When the screen goes away:
 * holder.close()
 * ```
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
        @Suppress("UNUSED_PARAMETER") startAfter: Job?,
    ) : this(config, engineFactory, plugins, environment)

    /** For `:cameraK`'s retained holder: the production constructor, starting after [startAfter]. */
    @InternalCameraKApi
    public constructor(
        config: CameraConfiguration,
        platformContext: CameraKPlatformContext,
        plugins: List<CameraKPlugin>,
        @Suppress("UNUSED_PARAMETER") startAfter: Job?,
    ) : this(config, platformContext, plugins)

    /**
     * The 1.x constructor. [controllerFactory] runs in the first start, on [coroutineScope]'s
     * dispatcher, and the holder's scope is a child of [coroutineScope]'s job. It checks no
     * permission, as 1.x did not.
     */
    @Deprecated(
        "Build the holder with CameraKStateHolder(config, platformContext, plugins), or with " +
            "rememberCameraKStateHolder in Compose.",
    )
    public constructor(
        cameraConfiguration: CameraConfiguration,
        controllerFactory: suspend () -> CameraController,
        coroutineScope: CoroutineScope,
    ) : this(
        cameraConfiguration,
        CameraEngineFactory { _, env -> LegacyControllerEngine(controllerFactory, env.dispatchers.default, env) },
        emptyList(),
        legacyEnvironment(coroutineScope),
    )

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

    private val configuration = config

    private val desiredHardware = MutableStateFlow(config.toDesiredHardware())

    // The one Ready value, over the engine's 1.x facade channel when it has one. Ready reads the
    // controller only when asked, because the 1.x constructor's engine creates it in its first start.
    private val readyState = CameraKState.Ready(engine as? LegacyControllerAccess)

    private val closing = atomic(false)

    /** Completes once the engine has closed after [close]. Public only because `:cameraK` is another module. */
    @InternalCameraKApi
    public val closeCompletion: Job = Job()

    // Set when the first session reaches Ready: from then until close, the 1.x facade is live.
    private val facadeLive = atomic(false)

    private val _events = MutableSharedFlow<CameraKEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Unsolicited notices, such as a plugin that failed. Lossy and not replayed: collect them to see
     * them. A command's outcome is its result, never an event.
     */
    public val events: SharedFlow<CameraKEvent> = _events.asSharedFlow()

    // Captures in flight, the capture commands' and the 1.x captureImage's together.
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
        readyState = readyState,
        desiredHardware = { desiredHardware.value },
        beforeReady = ::beforeReady,
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
     * The camera's properties, derived from what the engine reports, the captures in flight, the
     * recording and the configuration. Nothing else writes it: a command changes it only once the
     * platform reports the change. [CameraUIState.flashMode], [CameraUIState.torchMode] and the
     * camera fields are the platform's from the first bind on.
     */
    public val uiState: StateFlow<CameraUIState> = DerivedStateFlow(
        read = {
            uiStateOf(
                engine.hardware.value,
                engine.zoom.value,
                pendingCaptures.value,
                recording.state.value,
                recording.durationMs.value,
            )
        },
        changes = combine(
            engine.hardware,
            engine.zoom,
            pendingCaptures,
            recording.state,
            recording.durationMs,
            ::uiStateOf,
        ),
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

    private val updateUiStateWarned = atomic(false)

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
                commands.joinAll()
            }
        } catch (e: Throwable) {
            if (e.isFatal()) throw e
            CameraKLogger.e(TAG, "Closing the camera engine failed; the holder closed without it", e)
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

    /** Runs [block] in the holder's scope, which [close] cancels. For `bindToLifecycle`. */
    internal fun launchInHolderScope(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    private fun beforeReady() {
        if (closing.value) return
        facadeLive.value = true
        pluginHost.attachPending()
    }

    // The 1.x facade: the Ready value's controller, from the first Ready until close, over an engine that has one.
    private fun facade(): CameraController? = if (facadeLive.value && !closing.value) {
        (engine as? LegacyControllerAccess)?.legacyController
    } else {
        null
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
     * Where the recording is: Idle, then Starting, Recording, Paused and Stopping, and Finished after
     * any end, with the outcome. The holder owns it; [RecordingState.elapsed] reads the recorded time.
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

    private fun uiStateOf(
        hardware: HardwareState,
        zoom: ZoomState,
        capturing: Int,
        recording: RecordingState,
        recordedMs: Long,
    ): CameraUIState = legacyUiState(
        hardware = hardware,
        zoom = zoom,
        capturing = capturing,
        recording = recording,
        recordedMs = recordedMs,
        configuration = configuration,
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
    // The 1.x surface, kept until the slices that replace it
    // ═══════════════════════════════════════════════════════════════

    /**
     * Plugins' coroutine scope: a child of the holder's scope. A failure in one of its coroutines is
     * logged and reported as [CameraKEvent.PluginFailed] and cancels nothing else. [close] cancels
     * it.
     */
    @Deprecated("Plugins get their own scope from their plugin context.")
    public val pluginScope: CoroutineScope get() = pluginHost.scope

    /**
     * [start], then suspends until the camera is Ready. Throws what made the start fail, or an
     * [IllegalStateException] naming the error when it carries no cause.
     */
    @Deprecated("Call start() and observe cameraState.")
    public suspend fun initialize() {
        when (val outcome = lifecycle.awaitSettled(lifecycle.start())) {
            is CameraKState.Ready, is CameraKState.Interrupted -> Unit
            is CameraKState.Error ->
                throw outcome.error.cause
                    ?: IllegalStateException("The camera failed to start: ${outcome.error::class.simpleName}")
            CameraKState.Idle -> throw IllegalStateException("The camera was stopped before it was ready")
            CameraKState.Starting, CameraKState.Closed -> throw IllegalStateException("The holder is closed")
        }
    }

    /** Detaches every plugin and cancels the plugins' coroutines before it returns, then [close]s. */
    @Deprecated("Call close().", ReplaceWith("close()"))
    public fun shutdown() {
        pluginHost.detachAll()
        close()
    }

    /** Suspends until the camera is Ready, then returns the 1.x controller; null on Error, on Closed, or over an engine without one. */
    @Deprecated("Observe cameraState and issue commands on the holder.")
    public suspend fun getReadyCameraController(): CameraController? {
        val state = cameraState.first {
            it is CameraKState.Ready || it is CameraKState.Error || it is CameraKState.Closed
        }
        return if (state is CameraKState.Ready) facade() else null
    }

    /** The 1.x controller from the first Ready until close; null otherwise, and over an engine without one. */
    @Deprecated("Issue commands on the holder.")
    public fun getController(): CameraController? = facade()

    /** The 1.x controller's capabilities, or [CameraCapabilities.EMPTY] without one. */
    @Deprecated("Read the holder's capabilities.", ReplaceWith("capabilities.value"))
    public fun getCameraCapabilities(): CameraCapabilities =
        facade()?.getCameraCapabilities() ?: CameraCapabilities.EMPTY

    /** Captures to a file on the 1.x controller; emits [CameraKEvent.ImageCaptured] or [CameraKEvent.CaptureFailed]. */
    @Deprecated("Capture through the holder's capture command.")
    public fun captureImage() {
        val currentController = facade() ?: run {
            scope.launch { _events.emit(CameraKEvent.CaptureFailed(Exception("Camera not initialized"))) }
            return
        }
        pendingCaptures.update { it + 1 }
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                val result = currentController.takePictureToFile()
                _events.emit(CameraKEvent.ImageCaptured(result))
            } catch (e: CancellationException) {
                // close() cancelled the holder's scope: not a failure of the command.
                throw e
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "Capturing an image failed", e)
                _events.emit(CameraKEvent.CaptureFailed(e))
            } finally {
                pendingCaptures.update { it - 1 }
            }
        }
    }

    /** Cycles the flash mode on the 1.x controller. */
    @Deprecated("Replaced by the holder's flash commands.")
    public fun toggleFlashMode() {
        facade()?.toggleFlashMode()
    }

    /** Toggles the torch on the 1.x controller. */
    @Deprecated("Replaced by the holder's torch commands.")
    public fun toggleTorchMode() {
        facade()?.toggleTorchMode()
    }

    /** Switches between the front and back camera on the 1.x controller. */
    @Deprecated("Replaced by the holder's camera selection commands.")
    public fun toggleCameraLens() {
        facade()?.toggleCameraLens()
    }

    /** Emits [event] on [events]. Plugins use it to reach the app. */
    @Deprecated("Plugins report through their plugin context.")
    public suspend fun emitEvent(event: CameraKEvent) {
        _events.emit(event)
    }

    /**
     * Writes nothing: [uiState] is derived from what the platform reports. A caller built against
     * 1.x still links; its first call logs a warning.
     */
    @Deprecated(
        "uiState follows what the platform reports and cannot be written. Issue a command instead.",
        level = DeprecationLevel.ERROR,
    )
    public fun updateUIState(update: (CameraUIState) -> CameraUIState) {
        if (updateUiStateWarned.compareAndSet(expect = false, update = true)) {
            CameraKLogger.w(TAG, "updateUIState changes nothing: uiState follows what the platform reports")
        }
    }

    private companion object {
        const val TAG = "CameraK"
        const val EVENT_BUFFER = 64
        const val CAPTURE_BUFFER = 64
    }
}

/** The production constructor's environment: the platform's permission checker. */
internal fun productionEnvironment(context: CameraKPlatformContext): CameraKEnvironment =
    CameraKEnvironment(permissionChecker = platformPermissionChecker(context))

/** The engine did not close within [ENGINE_CLOSE_TIMEOUT]; logged, never thrown. */
internal class EngineCloseTimeoutException(timeout: Duration) :
    IllegalStateException("The camera engine did not close within $timeout")

// The 1.x constructor's environment: everything on the host scope's dispatcher, under its job, no permission check.
private fun legacyEnvironment(hostScope: CoroutineScope): CameraKEnvironment {
    val context = hostScope.coroutineContext
    val dispatcher = context[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.Default
    return CameraKEnvironment(
        dispatchers = CameraKDispatchers(main = dispatcher, default = dispatcher, io = dispatcher),
        parentJob = context[Job],
        permissionChecker = null,
    )
}

// The environment the holder gives its engine: its own, with the holder's job as the parent.
private fun CameraKEnvironment.under(parent: Job): CameraKEnvironment =
    CameraKEnvironment(dispatchers, timeSource, parent, permissionChecker).also { it.runtime = runtime }

private fun CameraConfiguration.toDesiredHardware(): DesiredHardware = DesiredHardware(
    selection = cameraId?.let { CameraSelection.ById(it) } ?: CameraSelection.ByLens(cameraLens, cameraDeviceType),
    flashMode = flashMode,
    torchMode = torchMode,
    zoomRatio = 1f,
    targetOrientation = null,
)

private fun DesiredHardware.with(
    selection: CameraSelection = this.selection,
    flashMode: FlashMode = this.flashMode,
    torchMode: TorchMode = this.torchMode,
    zoomRatio: Float = this.zoomRatio,
    targetOrientation: DeviceOrientation? = this.targetOrientation,
): DesiredHardware = DesiredHardware(selection, flashMode, torchMode, zoomRatio, targetOrientation)

// The 1.x data class, filled from the engine's reports, the capture counter, the recording and the
// configuration. lastError stays null: commands return their failures. The recorded time shows only
// while a recording is live, frozen while paused.
@Suppress("DEPRECATION")
private fun legacyUiState(
    hardware: HardwareState,
    zoom: ZoomState,
    capturing: Int,
    recording: RecordingState,
    recordedMs: Long,
    configuration: CameraConfiguration,
): CameraUIState = CameraUIState(
    zoomLevel = zoom.ratio,
    maxZoom = zoom.maxRatio,
    flashMode = hardware.flashMode,
    torchMode = hardware.torchMode,
    cameraLens = hardware.camera?.lens,
    imageFormat = configuration.imageFormat,
    qualityPrioritization = configuration.qualityPrioritization,
    cameraDeviceType = hardware.camera?.deviceType ?: CameraDeviceType.DEFAULT,
    isCapturing = capturing > 0,
    lastError = null,
    isRecording = recording is RecordingState.Recording || recording is RecordingState.Paused,
    isPaused = recording is RecordingState.Paused,
    recordingDurationMs = if (recording is RecordingState.Recording || recording is RecordingState.Paused) {
        recordedMs
    } else {
        0L
    },
    pendingCaptures = capturing,
    cameraId = hardware.camera?.id,
    targetOrientation = hardware.targetOrientation,
)

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

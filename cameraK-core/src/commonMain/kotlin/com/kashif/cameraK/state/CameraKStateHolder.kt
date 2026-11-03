@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.state

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.controller.CameraController
import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.CameraEngineFactory
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.LegacyControllerAccess
import com.kashif.cameraK.engine.LegacyControllerEngine
import com.kashif.cameraK.engine.createPlatformEngine
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.permissions.platformPermissionChecker
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.plugin.PluginHost
import com.kashif.cameraK.plugin.effectiveConfig
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

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

    private val desiredHardware = MutableStateFlow(config.toDesiredHardware())

    // The one Ready value, over the engine's 1.x facade channel when it has one. Ready reads the
    // controller only when asked, because the 1.x constructor's engine creates it in its first start.
    private val readyState = CameraKState.Ready(engine as? LegacyControllerAccess)

    private val closing = atomic(false)

    // Set when the first session reaches Ready: from then until close, the 1.x facade is live.
    private val facadeLive = atomic(false)

    private val _events = MutableSharedFlow<CameraKEvent>()

    /** One-shot events. Not replayed: collect them to see them. */
    public val events: SharedFlow<CameraKEvent> = _events.asSharedFlow()

    private val _uiState = MutableStateFlow(CameraUIState())

    /** The camera's properties as the 1.x commands last set them, seeded from the 1.x facade at the first Ready. */
    public val uiState: StateFlow<CameraUIState> = _uiState.asStateFlow()

    private val pluginHost = PluginHost(this, scope) { _events.emit(it) }

    private val lifecycle = SessionLifecycle(
        engine = engine,
        permissionChecker = environment.permissionChecker,
        scope = scope,
        readyState = readyState,
        desiredHardware = { desiredHardware.value },
        beforeReady = ::beforeReady,
    )

    /**
     * The camera's lifecycle: [CameraKState.Idle] until [start], then Starting, and Ready once the
     * platform confirms the camera is open. [CameraKState.Closed] after [close].
     */
    public val cameraState: StateFlow<CameraKState> = lifecycle.state

    /** The device's physical orientation, as the engine reports it. */
    public val deviceOrientation: StateFlow<DeviceOrientation> = engine.deviceOrientation

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

    /** Closes the camera and returns to Idle. A start in progress is cancelled and publishes no error. */
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
     * Releases the camera, once: publishes [CameraKState.Closed], detaches the plugins, then closes
     * the engine, waiting at most a bounded time for it. Returns at once; [closeAndJoin] waits.
     */
    override fun close() {
        if (!closing.compareAndSet(expect = false, update = true)) return
        lifecycle.enterClosed()
        scope.launch(start = CoroutineStart.ATOMIC) {
            withContext(NonCancellable) {
                lifecycle.shutDown()
                pluginHost.detachAll()
                withTimeoutOrNull(ENGINE_CLOSE_TIMEOUT) { engine.closeAndJoin() }
                    ?: CameraKLogger.e(
                        TAG,
                        "Closing the camera engine timed out; the holder closed without waiting for it",
                        EngineCloseTimeoutException(ENGINE_CLOSE_TIMEOUT),
                    )
            }
            scope.cancel()
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

    private fun beforeReady() {
        if (closing.value) return
        if (facadeLive.compareAndSet(expect = false, update = true)) facade()?.let(::seedUiState)
        pluginHost.attachPending()
    }

    // The 1.x facade: the Ready value's controller, from the first Ready until close, over an engine that has one.
    private fun facade(): CameraController? = if (facadeLive.value && !closing.value) {
        (engine as? LegacyControllerAccess)?.legacyController
    } else {
        null
    }

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
        resetRecordingState()
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
    @Deprecated("Use the engine's capabilities through the holder.")
    public fun getCameraCapabilities(): CameraCapabilities =
        facade()?.getCameraCapabilities() ?: CameraCapabilities.EMPTY

    /** Captures to a file on the 1.x controller; emits [CameraKEvent.ImageCaptured] or [CameraKEvent.CaptureFailed]. */
    @Deprecated("Capture through the holder's capture command.")
    public fun captureImage() {
        val currentController = facade() ?: run {
            scope.launch { _events.emit(CameraKEvent.CaptureFailed(Exception("Camera not initialized"))) }
            return
        }
        scope.launch {
            try {
                _uiState.value = _uiState.value.copy(isCapturing = true)
                val result = currentController.takePictureToFile()
                _events.emit(CameraKEvent.ImageCaptured(result))
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "Capturing an image failed", e)
                _events.emit(CameraKEvent.CaptureFailed(e))
                _uiState.value = _uiState.value.copy(lastError = "Capture failed: ${e.message}")
            } finally {
                _uiState.value = _uiState.value.copy(isCapturing = false)
            }
        }
    }

    /** Sets the zoom ratio on the 1.x controller. */
    @Deprecated("Replaced by the holder's suspend setZoom command.")
    public fun setZoom(zoom: Float) {
        val currentController = facade() ?: return
        currentController.setZoom(zoom)
        _uiState.value = _uiState.value.copy(zoomLevel = currentController.getZoom())
    }

    /** Cycles the flash mode on the 1.x controller. */
    @Deprecated("Replaced by the holder's flash commands.")
    public fun toggleFlashMode() {
        val currentController = facade() ?: return
        currentController.toggleFlashMode()
        _uiState.value = _uiState.value.copy(flashMode = currentController.getFlashMode())
    }

    /** Sets the flash mode on the 1.x controller. */
    @Deprecated("Replaced by the holder's suspend setFlashMode command.")
    public fun setFlashMode(mode: FlashMode) {
        val currentController = facade() ?: return
        currentController.setFlashMode(mode)
        _uiState.value = _uiState.value.copy(flashMode = mode)
    }

    /** Toggles the torch on the 1.x controller. */
    @Deprecated("Replaced by the holder's torch commands.")
    public fun toggleTorchMode() {
        val currentController = facade() ?: return
        currentController.toggleTorchMode()
        _uiState.value = _uiState.value.copy(torchMode = currentController.getTorchMode())
    }

    /** Sets the torch mode on the 1.x controller. */
    @Deprecated("Replaced by the holder's suspend setTorchMode command.")
    public fun setTorchMode(mode: TorchMode) {
        val currentController = facade() ?: return
        currentController.setTorchMode(mode)
        _uiState.value = _uiState.value.copy(torchMode = mode)
    }

    /** Switches between the front and back camera on the 1.x controller. */
    @Deprecated("Replaced by the holder's camera selection commands.")
    public fun toggleCameraLens() {
        val currentController = facade() ?: return
        currentController.toggleCameraLens()
        _uiState.value = _uiState.value.copy(cameraLens = currentController.getCameraLens())
    }

    /** Locks the output orientation on the 1.x controller; null follows the device. */
    @Deprecated("Replaced by the holder's suspend setTargetOrientation command.")
    public fun setTargetOrientation(orientation: DeviceOrientation?) {
        facade()?.setTargetOrientation(orientation)
    }

    private var recordingTimerJob: Job? = null

    private fun resetRecordingState() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        _uiState.value = _uiState.value.copy(isRecording = false, isPaused = false, recordingDurationMs = 0L)
    }

    /**
     * Starts a recording on the 1.x controller. Emits [CameraKEvent.RecordingStarted], and
     * [CameraKEvent.RecordingMaxDurationReached] when [VideoConfiguration.maxDurationMs] ends it.
     */
    @Deprecated("Replaced by the holder's recording commands.")
    public fun startRecording(configuration: VideoConfiguration = VideoConfiguration()) {
        // A second start while recording would orphan the timer and the platform recorder.
        if (_uiState.value.isRecording) {
            scope.launch { _events.emit(CameraKEvent.RecordingFailed(Exception("Recording already in progress"))) }
            return
        }
        val currentController = facade() ?: run {
            scope.launch { _events.emit(CameraKEvent.RecordingFailed(Exception("Camera not initialized"))) }
            return
        }
        scope.launch {
            try {
                val path = currentController.startRecording(configuration)
                _uiState.value = _uiState.value.copy(isRecording = true, isPaused = false, recordingDurationMs = 0L)
                _events.emit(CameraKEvent.RecordingStarted(path))
                recordingTimerJob = scope.launch { tickRecording(currentController, configuration, path) }
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "Starting a recording failed", e)
                resetRecordingState()
                _uiState.value = _uiState.value.copy(lastError = "Recording failed: ${e.message}")
                _events.emit(CameraKEvent.RecordingFailed(e))
            }
        }
    }

    // The 1.x duration ticker: pause-aware elapsed time, and the auto-stop at maxDurationMs.
    @OptIn(ExperimentalTime::class)
    private suspend fun CoroutineScope.tickRecording(
        controller: CameraController,
        configuration: VideoConfiguration,
        path: String,
    ) {
        val startMs = Clock.System.now().toEpochMilliseconds()
        var pausedAccumulatorMs = 0L
        var pauseStartMs = 0L
        while (isActive) {
            delay(250L)
            val isPaused = _uiState.value.isPaused
            val wasPaused = pauseStartMs > 0L
            if (isPaused && !wasPaused) {
                pauseStartMs = Clock.System.now().toEpochMilliseconds()
            } else if (!isPaused && wasPaused) {
                pausedAccumulatorMs += Clock.System.now().toEpochMilliseconds() - pauseStartMs
                pauseStartMs = 0L
            }
            if (isPaused) continue
            val elapsed = Clock.System.now().toEpochMilliseconds() - startMs - pausedAccumulatorMs
            _uiState.value = _uiState.value.copy(recordingDurationMs = elapsed)

            if (configuration.maxDurationMs > 0 && elapsed >= configuration.maxDurationMs) {
                // A manual stopRecording may have won the race.
                if (!_uiState.value.isRecording) break
                val result = controller.stopRecording()
                resetRecordingState()
                _events.emit(CameraKEvent.RecordingMaxDurationReached(path, elapsed))
                _events.emit(CameraKEvent.RecordingStopped(result))
                break
            }
        }
    }

    /** Stops the recording on the 1.x controller; emits [CameraKEvent.RecordingStopped]. */
    @Deprecated("Replaced by the holder's recording commands.")
    public fun stopRecording() {
        if (!_uiState.value.isRecording) return
        val currentController = facade() ?: return
        scope.launch {
            try {
                // The timer goes first, so it cannot auto-stop the same recording.
                recordingTimerJob?.cancel()
                recordingTimerJob = null
                val result = currentController.stopRecording()
                resetRecordingState()
                _events.emit(CameraKEvent.RecordingStopped(result))
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "Stopping a recording failed", e)
                resetRecordingState()
                _uiState.value = _uiState.value.copy(lastError = "Stop recording failed: ${e.message}")
                _events.emit(CameraKEvent.RecordingFailed(e))
            }
        }
    }

    /** Pauses the recording on the 1.x controller. */
    @Deprecated("Replaced by the holder's recording commands.")
    public fun pauseRecording() {
        val currentController = facade() ?: return
        scope.launch {
            try {
                currentController.pauseRecording()
                _uiState.value = _uiState.value.copy(isPaused = true)
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "Pausing a recording failed", e)
                _uiState.value = _uiState.value.copy(lastError = "Pause recording failed: ${e.message}")
                _events.emit(CameraKEvent.RecordingFailed(e))
            }
        }
    }

    /** Resumes a paused recording on the 1.x controller. */
    @Deprecated("Replaced by the holder's recording commands.")
    public fun resumeRecording() {
        val currentController = facade() ?: return
        scope.launch {
            try {
                currentController.resumeRecording()
                _uiState.value = _uiState.value.copy(isPaused = false)
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "Resuming a recording failed", e)
                _uiState.value = _uiState.value.copy(lastError = "Resume recording failed: ${e.message}")
                _events.emit(CameraKEvent.RecordingFailed(e))
            }
        }
    }

    /** Emits [event] on [events]. Plugins use it to reach the app. */
    @Deprecated("Plugins report through their plugin context.")
    public suspend fun emitEvent(event: CameraKEvent) {
        _events.emit(event)
    }

    /** Replaces [uiState] with [update] applied to it. */
    @Deprecated("uiState is read-only in 2.0; it follows what the platform reports.")
    public fun updateUIState(update: (CameraUIState) -> CameraUIState) {
        _uiState.value = update(_uiState.value)
    }

    private fun seedUiState(controller: CameraController) {
        try {
            _uiState.value = CameraUIState(
                zoomLevel = controller.getZoom(),
                maxZoom = controller.getMaxZoom(),
                flashMode = controller.getFlashMode(),
                torchMode = controller.getTorchMode(),
                cameraLens = controller.getCameraLens(),
                imageFormat = controller.getImageFormat(),
                qualityPrioritization = controller.getQualityPrioritization(),
                cameraDeviceType = controller.getPreferredCameraDeviceType(),
                isCapturing = false,
                lastError = null,
            )
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Reading the 1.x controller's settings failed; uiState keeps its defaults", e)
        }
    }

    private companion object {
        const val TAG = "CameraK"
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

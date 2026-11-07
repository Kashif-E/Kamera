@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.TorchState
import androidx.camera.core.UseCase
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.kashif.cameraK.capabilities.AndroidLensDescriptor
import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensEnumerator
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineRecordingEvent
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.HardwareState
import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.engine.TraceNames
import com.kashif.cameraK.engine.Tracer
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.ZoomState
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import com.kashif.cameraK.video.VideoQuality
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val TAG = "CameraK"

/**
 * The Android engine over CameraX (design §2.4). It is its own [LifecycleOwner]: [start] moves its
 * registry to RESUMED, [stop] back to CREATED with its use cases still bound, so the next start
 * reopens the camera without a rebind, and [close] to DESTROYED. It binds and unbinds only its own
 * use cases. Every CameraX binding call, the registry and every LiveData observer run on
 * `environment.dispatchers.main`.
 *
 * The session's state comes from CameraX's `cameraInfo.cameraState`, observed on every camera a bind
 * returns and mapped by [CameraXStateMapper]: OPEN is Open, a recoverable error is Interrupted until
 * CameraX reopens, or Failed after [RECOVERY_TIMEOUT], and a critical error is Failed. A report
 * reaches the state only while its session is live, so nothing a stopped, closed or failed session's
 * camera says changes it.
 *
 * [hardware] and [zoom] are what CameraX reports: torch and zoom from the bound camera's
 * `torchState` and `zoomState`, flash from a read-back of the bound ImageCapture after each set and
 * each bind (design §2.3 rule 5). Flash, torch and zoom are commands for an open camera, NotReady
 * otherwise; the target orientation is configuration, taken in any state but closed. The desired
 * hardware is re-applied once the camera opens and after every bind made while it runs.
 *
 * Stills and recordings are files (Decision 10): `outputDirectory`, used as given, else the app-owned
 * `filesDir/CameraK`; publishing to the gallery is the media saver plugin's job. [capture] returns the written file, a real PNG for a
 * PNG configuration; HEIC fails [start] with `Unsupported(IMAGE_FORMAT)`. Each recording is observed
 * from CameraX's Start to its Finalize on [recordingEvents] (§5); VideoCapture is built off Main and
 * bound only for a recording or from [prepareRecording] until the engine stops (§2.4).
 *
 * [frames] is the [FramePipeline] (§8): one ImageAnalysis in the configured analysis format and size,
 * bound from the first bind when `frameAnalysis` is set, otherwise by the first registration's one
 * late bind, never during a recording (the recording's end binds it). Registering and unregistering
 * never rebind.
 */
internal class AndroidCameraEngine(
    private val config: CameraConfiguration,
    internal val context: Context,
    private val environment: CameraKEnvironment,
    internal val backend: CameraXBackend = CameraXBackend.Default,
    lensSource: () -> List<AndroidLensDescriptor> = { LensEnumerator.snapshot(context) },
    private val imageProcessingExecutor: ExecutorService =
        Executors.newFixedThreadPool(2, cameraKThreads("CameraK-image-processing")),
    private val analyzerExecutor: ExecutorService =
        Executors.newSingleThreadExecutor(cameraKThreads("CameraK-analyzer")),
) : CameraEngine,
    LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val main: CoroutineDispatcher = environment.dispatchers.main

    // Runs at once when already on the main thread, so a call made there takes effect before it
    // returns. A test dispatcher has no immediate form and is used as it is.
    private val mainImmediate: CoroutineDispatcher = immediateFormOf(main)

    private val scope = CoroutineScope(SupervisorJob(environment.parentJob) + main)

    // Runs the release, which outlives the engine's scope: the release cancels that scope last.
    private val teardownScope = CoroutineScope(
        SupervisorJob() + main +
            CoroutineExceptionHandler { _, e -> CameraKLogger.e(TAG, "Releasing the camera failed", e) },
    )

    /** The tracer the engine writes its start and first-frame intervals to. */
    internal val tracer: Tracer = environment.runtime.tracer ?: AndroidTracer

    private val closed = atomic(false)

    /** True from [close] on. */
    internal val isClosed: Boolean get() = closed.value

    private val _state = MutableStateFlow<EngineState>(EngineState.Closed)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _hardware = MutableStateFlow(HardwareState(FlashMode.OFF, TorchMode.OFF, null, null))
    override val hardware: StateFlow<HardwareState> = _hardware.asStateFlow()

    private val _zoom = MutableStateFlow(ZoomState(1f, 1f, 1f))
    override val zoom: StateFlow<ZoomState> = _zoom.asStateFlow()

    private val _deviceOrientation = MutableStateFlow(DeviceOrientation.PORTRAIT)
    override val deviceOrientation: StateFlow<DeviceOrientation> = _deviceOrientation.asStateFlow()

    override val capabilities: StateFlow<CameraCapabilities> get() = catalog.capabilities

    override val recordingEvents: Flow<EngineRecordingEvent> get() = videoRecorder.events

    override val frames: FrameSource get() = framePipeline

    private val previewSource =
        EnginePreviewSource(config.aspectRatio, onMain = ::onMain, onDisplayAttached = ::onDisplayChanged)
    override val preview: PlatformPreviewSource get() = previewSource

    // CameraX's own callbacks (the provider, frame-analysis and capture results the engine reads) run
    // on the main dispatcher.
    private val mainExecutor = main.asExecutor()

    internal val orientation = OrientationTracker(context)
    internal val binder = UseCaseBinder(
        context = context,
        lifecycleOwner = this,
        aspectRatio = config.aspectRatio,
        // The binder takes the size as a pair; the configuration has validated it.
        targetResolution = config.targetResolution?.let { it.width to it.height },
        isReleased = { closed.value },
        display = { previewSource.display },
        onDisplayChanged = ::onDisplayChanged,
    )

    private val files = OutputFileFactory(context, config.outputDirectory, environment.dispatchers.io)
    private val imageProcessing = imageProcessingExecutor.asCoroutineDispatcher()
    internal val stillCapturer = StillCapturer(
        backend = backend,
        files = files,
        imageProcessingExecutor = imageProcessingExecutor,
        callbackExecutor = mainExecutor,
        qualityPriority = config.qualityPrioritization,
        format = config.imageFormat,
        tracer = { tracer },
        isReleased = { closed.value },
        metadata = ::captureMetadata,
        boundLens = { boundLens },
    )
    internal val hardwareControls = HardwareControls(
        context = context,
        flashMode = config.flashMode,
        torchMode = config.torchMode,
        camera = { binder.camera },
        imageCapture = { stillCapturer.imageCapture },
        readFlash = backend::flashModeOf,
    )
    internal val framePipeline = FramePipeline(
        backend = backend,
        analyzerExecutor = analyzerExecutor,
        sessionFormat = config.analysisFormat,
        newWorker = { name -> environment.runtime.newWorker(name) },
        resolutionSelector = binder.analysisResolutionSelector(config.analysisResolution),
    )
    internal val videoRecorder =
        VideoRecorder(
            context = context,
            backend = backend,
            files = files,
            host = VideoHost(),
            background = environment.dispatchers.default,
            io = environment.dispatchers.io,
            main = main,
            callbackExecutor = mainExecutor,
        )

    /** The physical lens every use case of the current bind pins, or null. */
    internal var pinnedPhysicalId: String? = null
        private set

    /** The facing of the current bind; the still capturer's front-camera mirroring reads it. */
    internal var boundLens: CameraLens = config.cameraLens ?: CameraLens.BACK
        private set

    private val catalog = LensCatalog(
        lensSource = lensSource,
        backend = backend,
        scope = scope,
        background = environment.dispatchers.default,
        mainImmediate = mainImmediate,
        isReleased = { closed.value },
        boundCamera = { binder.camera },
    )

    private var provider: CameraBinding? = null

    // Main thread only, as everything below: the live session, the camera it is bound on, and the
    // observer of that camera's state.
    private var session: Session? = null
    private var boundCamera: ResolvedCamera? = null
    private val observers = mutableListOf<() -> Unit>()

    // Set when a portrait↔landscape flip is detected during recording (when rebinding would tear
    // down the recording). The deferred rebind runs once the recording finalizes.
    private var pendingViewPortRebind = false

    private var teardown: Job? = null
    private val awaitingFirstFrame = atomic(false)

    // A late bind of the ImageAnalysis a registration asked for is queued. Main thread.
    private var analysisBindPending = false

    init {
        // Declared up front, so the first bind includes the ImageAnalysis and a plugin that registers
        // later only joins it (Decision 14).
        if (config.frameAnalysis) framePipeline.requestAnalysis()
        framePipeline.onConsumersChanged = { onMain(::bindAnalysisOrApplyConsumers) }
    }

    /** One start's generation: every platform report checks it is still the live one before it acts. */
    private class Session {
        /** Success once CameraX accepted the start (open or interrupted), or the failure that ended it. */
        val opened = CompletableDeferred<CameraKResult<Unit>>()
        var failed = false
        var recovery: Job? = null
        var startTrace: AutoCloseable? = null
        var firstFrameTrace: AutoCloseable? = null
    }

    override suspend fun start(desired: DesiredHardware): CameraKResult<Unit> {
        if (closed.value) return CLOSED
        return withContext(mainImmediate) { startOnMain(desired) }
    }

    private suspend fun startOnMain(desired: DesiredHardware): CameraKResult<Unit> {
        if (closed.value) return CLOSED
        session?.let { live -> if (!live.failed) return live.opened.await() }

        val live = Session()
        session = live
        _state.value = EngineState.Opening
        // Android cannot write HEIC, and a start never accepts a format it would ignore (§6.1).
        if (config.imageFormat == ImageFormat.HEIC) {
            CameraKLogger.e(TAG, "ImageFormat.HEIC is not supported on Android")
            return fail(live, CameraKError.Unsupported(CameraFeature.IMAGE_FORMAT))
        }
        // Created off Main ahead of the first capture, which then claims its file without waiting.
        scope.launch { files.ensureDirectory(files.defaultDirectory) }
        live.startTrace = tracer.beginInterval(TraceNames.START)
        live.firstFrameTrace = tracer.beginInterval(TraceNames.FIRST_FRAME)
        awaitingFirstFrame.value = true
        applyDesired(desired)
        try {
            // A stop or a close completes `opened` while the start waits, which ends the wait.
            if (awaitUnlessEnded(live, catalog.enumeration) == null) return live.opened.await()
            val resolved = catalog.resolve(desired.selection)
                ?: return fail(live, CameraKError.NoCamera(catalog.lensOf(desired.selection)))
            // The display listener runs only while started, so a display that turned while stopped
            // left the ViewPort stale: the start rebinds then, even on the same camera.
            val stale = viewPortStale() || videoRecorder.videoBindStale || framePipeline.analysisMissing
            if (resolved != boundCamera || stale) {
                val bindProvider = awaitUnlessEnded(live, providerAsync()) ?: return live.opened.await()
                val error = bindProvider.fold(
                    onSuccess = { binding ->
                        val camera = catalog.offered(resolved, binding)
                        if (camera == null) {
                            CameraKError.NoCamera(catalog.lensOf(desired.selection))
                        } else {
                            bind(camera, binding)
                        }
                    },
                    onFailure = {
                        CameraKLogger.e(TAG, "Obtaining the camera provider failed", it)
                        CameraKError.Internal(it)
                    },
                )
                if (error != null) return fail(live, error)
            } else {
                // A half turn keeps the ViewPort but not the capture rotation, and the target may have changed.
                applyCaptureRotation()
            }
            if (session !== live) return live.opened.await()
            orientation.start(::onDeviceOrientation)
            binder.listenForDisplayChanges()
            registry.currentState = Lifecycle.State.RESUMED
            return live.opened.await()
        } catch (e: CancellationException) {
            if (session === live) endSession(live)
            throw e
        }
    }

    override suspend fun stop() {
        if (closed.value) return
        withContext(mainImmediate) {
            // A recording is finalized before the camera closes (§2.3 rule 7).
            withContext(NonCancellable) { videoRecorder.stopForShutdown() }
            val live = session
            if (live != null) endSession(live) else _state.value = EngineState.Closed
        }
    }

    override suspend fun selectCamera(selection: CameraSelection): CameraKResult<LensInfo> {
        if (closed.value) return CLOSED
        return withContext(mainImmediate) {
            // A switch asked for while the start is in flight runs once CameraX has accepted it.
            session?.takeIf { !it.failed }?.opened?.let { opened ->
                val started = opened.await()
                if (started is CameraKResult.Failure) return@withContext started
            }
            val live = session
            when {
                closed.value -> CLOSED
                live == null || live.failed -> CameraKResult.Failure(CameraKError.NotReady)
                videoRecorder.isRecording -> {
                    CameraKLogger.w(TAG, "Ignoring lens switch while recording")
                    CameraKResult.Failure(CameraKError.Busy(CameraOperation.LENS))
                }
                else -> tracer.beginInterval(TraceNames.LENS_SWITCH).use { switchTo(live, selection) }
            }
        }
    }

    private suspend fun switchTo(live: Session, selection: CameraSelection): CameraKResult<LensInfo> {
        catalog.enumeration.await()
        val resolved = catalog.resolve(selection)
            ?: return CameraKResult.Failure(CameraKError.NoCamera(catalog.lensOf(selection)))
        if (resolved != boundCamera) {
            val binding = checkNotNull(provider) { "a live session has its provider" }
            val camera = catalog.offered(resolved, binding)
                ?: return CameraKResult.Failure(CameraKError.NoCamera(catalog.lensOf(selection)))
            if (camera != boundCamera) {
                val error = bind(camera, binding)
                if (error != null) {
                    return fail(live, error)
                }
            }
        }
        val camera = _hardware.value.camera ?: return CameraKResult.Failure(
            CameraKError.Internal(IllegalStateException("the bound camera is not one the capabilities list")),
        )
        return CameraKResult.Success(camera)
    }

    override suspend fun setFlashMode(mode: FlashMode): CameraKResult<FlashMode> = command {
        if (mode != FlashMode.OFF && hardwareControls.hasFlashUnit() != true) {
            val feature = if (mode == FlashMode.AUTO) CameraFeature.FLASH_AUTO else CameraFeature.FLASH_ON
            return@command CameraKResult.Failure(CameraKError.Unsupported(feature))
        }
        // No observer reports flash: the bound ImageCapture's mode, read back, is the report.
        val readBack = hardwareControls.setFlashMode(mode) ?: return@command CameraKResult.Failure(
            CameraKError.Internal(IllegalStateException("an open camera has no ImageCapture")),
        )
        publishHardware(flashMode = readBack)
        CameraKResult.Success(readBack)
    }

    override suspend fun capture(): CameraKResult<CapturedImage> = openCommand {
        when (val saved = stillCapturer.capture()) {
            is CameraKResult.Failure -> saved
            is CameraKResult.Success -> {
                val file = saved.value
                // Read from the written file, so a 1:1 capture reports the square CameraX wrote.
                val (width, height) = withContext(imageProcessing) {
                    uprightSize(file, jpeg = stillCapturer.format != ImageFormat.PNG)
                }
                CameraKResult.Success(CapturedImage(file.absolutePath, stillCapturer.format, width, height))
            }
        }
    }

    override suspend fun setZoom(ratio: Float): CameraKResult<ZoomState> = command {
        val range = hardwareControls.reportedZoomRange()
            ?: _hardware.value.camera?.let { it.minZoom..it.maxZoom }
            ?: 1f..1f
        val min = range.start
        val max = range.endInclusive
        when {
            // A fixed zoom has nothing to clamp to: its one ratio, or nothing.
            min >= max && ratio != min -> CameraKResult.Failure(CameraKError.Unsupported(CameraFeature.ZOOM))
            else -> {
                // CameraX fails setZoomRatio out of range; a pinch overshoots its ends, so clamp.
                val clamped = ratio.coerceIn(min, max)
                hardwareControls.setZoom(clamped)
                CameraKResult.Success(ZoomState(clamped, min, max))
            }
        }
    }

    override suspend fun setTorchMode(mode: TorchMode): CameraKResult<TorchMode> = command {
        when {
            mode == TorchMode.AUTO -> CameraKResult.Failure(CameraKError.Unsupported(CameraFeature.TORCH))
            mode == TorchMode.ON && hardwareControls.hasFlashUnit() != true ->
                CameraKResult.Failure(CameraKError.Unsupported(CameraFeature.TORCH))
            else -> {
                // hardware follows CameraX's torchState, not this request.
                hardwareControls.setTorchMode(mode)
                CameraKResult.Success(mode)
            }
        }
    }

    override suspend fun setTargetOrientation(orientation: DeviceOrientation?): CameraKResult<Unit> {
        if (closed.value) return CLOSED
        return withContext(mainImmediate) {
            if (closed.value) {
                CLOSED
            } else {
                applyTargetOrientation(orientation)
                CameraKResult.Success(Unit)
            }
        }
    }

    // A command that needs the camera open, which may suspend: on the main thread, NotReady otherwise.
    private suspend fun <T> openCommand(block: suspend () -> CameraKResult<T>): CameraKResult<T> {
        if (closed.value) return CLOSED
        return withContext(mainImmediate) {
            when {
                closed.value -> CLOSED
                !isOpen -> NOT_READY
                else -> block()
            }
        }
    }

    // A command on what is already recording, in any state but closed: on the main thread.
    private suspend fun <T> recordingCommand(block: suspend () -> T): T? {
        if (closed.value) return null
        return withContext(mainImmediate) { if (closed.value) null else block() }
    }

    // A hardware command: run on the main thread while the camera is open, NotReady otherwise.
    private suspend fun <T> command(block: () -> CameraKResult<T>): CameraKResult<T> {
        if (closed.value) return CLOSED
        return withContext(mainImmediate) {
            when {
                closed.value -> CLOSED
                !isOpen -> NOT_READY
                else -> block()
            }
        }
    }

    override suspend fun prepareRecording(config: VideoConfiguration): CameraKResult<Unit> =
        openCommand { videoRecorder.prepare(config) }

    /**
     * Starts a recording and returns its file's path once CameraX reports the start (§5). The quality
     * is checked against the bound camera's set: one it names other qualities only refuses with
     * `Unsupported(VIDEO_QUALITY)`, and a set the query could not read is unknown, so the quality goes
     * to CameraX with its `lowerQualityOrHigherThan` fallback, as 1.x did.
     *
     * While CameraX's Finalize of a recording it ended by itself (a limit, an error) is still being
     * read by the playable probe, that recording is still the active one, so a start returns
     * `Busy(RECORD_START)` until its Finalized is out.
     */
    override suspend fun startRecording(config: VideoConfiguration): CameraKResult<String> =
        openCommand { videoRecorder.start(config) }

    override suspend fun stopRecording(): CameraKResult<RecordedVideo> =
        recordingCommand { videoRecorder.stop() } ?: CLOSED

    override suspend fun pauseRecording(): CameraKResult<Unit> = recordingCommand { videoRecorder.pause() } ?: CLOSED

    override suspend fun resumeRecording(): CameraKResult<Unit> = recordingCommand { videoRecorder.resume() } ?: CLOSED

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        session?.opened?.complete(CLOSED)
        _state.value = EngineState.Closed
        teardown = teardownScope.launch(NonCancellable) { release() }
    }

    override suspend fun closeAndJoin() {
        close()
        teardown?.join()
    }

    /** The configuration the engine was built with. */
    internal val configuration: CameraConfiguration get() = config

    /** Runs [block] on the main dispatcher, at once when already there; nothing after close. */
    internal fun onMain(block: () -> Unit) {
        scope.launch(mainImmediate) { block() }
    }

    /** Launches [block] on the engine's scope on the main dispatcher, at once when already there. */
    internal fun launch(block: suspend () -> Unit) {
        scope.launch(mainImmediate) { block() }
    }

    /** Whether the live session's camera is open, the only state that takes hardware commands. Main thread. */
    internal val isOpen: Boolean get() = session?.failed == false && _state.value == EngineState.Open

    /**
     * Sets the one target orientation captures follow, applies it to the bound outputs and, while
     * Open, reports it; before Open the next bind applies it and the open reports it. Main thread.
     */
    internal fun applyTargetOrientation(target: DeviceOrientation?) {
        if (closed.value) return
        orientation.targetOrientation = target
        if (binder.camera != null) applyCaptureRotation()
        if (_state.value == EngineState.Open) publishHardware(targetOrientation = target)
    }

    /**
     * Attaches or detaches the frame multiplexer for the current consumers, or, when a registration
     * asked for an ImageAnalysis the bind lacks, binds it once (Decision 14's late bind). Before the
     * first bind, and during a recording, it waits: that bind, or the one the recording's end makes
     * ([VideoHost.onNoActiveRecording]), includes it. Main thread.
     */
    private fun bindAnalysisOrApplyConsumers() {
        if (closed.value) return
        if (!framePipeline.analysisMissing) {
            framePipeline.applyConsumers()
            return
        }
        if (session == null || !isBound || videoRecorder.isRecording || analysisBindPending) return
        analysisBindPending = true
        scope.launch(mainImmediate) {
            try {
                if (framePipeline.analysisMissing) rebind()
            } finally {
                analysisBindPending = false
            }
        }
    }

    /** Rebinds the bound camera with the current use cases, if a session has one. Main thread. */
    internal suspend fun rebind() {
        val live = session ?: return
        val camera = boundCamera ?: return
        val error = bind(camera, provider ?: return)
        if (error != null) fail<Unit>(live, error)
    }

    /** Whether a bind has given the session its camera. */
    internal val isBound: Boolean get() = binder.camera != null

    // ── Session ────────────────────────────────────────────────────────────────────────────────

    // Stored only: the camera's control is inactive until it opens, which applies them. A start that
    // names no target orientation keeps the one stored while the engine was closed or stopped.
    private fun applyDesired(desired: DesiredHardware) {
        hardwareControls.store(desired.flashMode, desired.torchMode, desired.zoomRatio)
        desired.targetOrientation?.let { orientation.targetOrientation = it }
    }

    /** Binds the use cases on [resolved]; null when bound, else the error. Main thread. */
    private fun bind(resolved: ResolvedCamera, binding: CameraBinding): CameraKError? {
        binder.useProvider(binding)
        stopObserving()
        boundCamera = null
        val resolutionSelector = binder.resolutionSelector()
        val pinned = resolved.pinnedPhysicalId
        pinnedPhysicalId = pinned
        boundLens = resolved.lens

        val preview = binder.buildPreview(resolutionSelector, pinned) { backend.onPreviewFrame(it, ::onPreviewFrame) }
        previewSource.bindTo(preview)
        stillCapturer.configure(resolutionSelector, hardwareControls.flashMode, pinned)
        // VideoCapture joins the group only while a recording needs it or it was prepared.
        val video = videoRecorder.useCaseForBind()
        catalog.forgetBoundQualities()
        // Rebuilt rather than reused: its Camera2Interop pin belongs to the bind it was built for.
        framePipeline.analysis = if (framePipeline.analysisRequested) framePipeline.build(pinned) else null
        applyCaptureRotation()

        val useCases = buildList<UseCase> {
            add(preview)
            add(checkNotNull(stillCapturer.imageCapture))
            video?.let { add(it) }
            framePipeline.analysis?.let { add(it) }
        }
        val camera = try {
            binder.bindGroup(resolved, useCases)
        } catch (e: SecurityException) {
            CameraKLogger.e(TAG, "Binding the camera was refused", e)
            framePipeline.onBound(false)
            return CameraKError.Permission(
                CameraKPermission.CAMERA,
                PermissionStatus.Denied(shouldShowRationale = false),
                e,
            )
        } catch (e: IllegalArgumentException) {
            framePipeline.onBound(false)
            if (video != null) {
                // CameraX found no stream combination that includes VideoCapture (§4.1).
                CameraKLogger.e(TAG, "CameraX refused the use cases with VideoCapture", e)
                return CameraKError.Unsupported(CameraFeature.VIDEO_QUALITY, e)
            }
            CameraKLogger.e(TAG, "No camera matches the selection", e)
            return CameraKError.NoCamera(resolved.lens, e)
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Binding the use cases failed", e)
            framePipeline.onBound(false)
            return CameraKError.Internal(e)
        }
        boundCamera = resolved
        framePipeline.onBound(framePipeline.analysis != null)
        if (video == null) videoRecorder.onBoundWithoutVideo()
        catalog.queryVideoQualities(camera)
        hardwareControls.revalidateFlash()
        _hardware.value = HardwareState(
            flashMode = hardwareControls.boundFlashMode() ?: hardwareControls.flashMode,
            torchMode = if (camera.cameraInfo.torchState.value == TorchState.ON) TorchMode.ON else TorchMode.OFF,
            camera = catalog.withFlashUnitOf(camera, catalog.reportedLens(resolved, camera, binding)),
            targetOrientation = orientation.targetOrientation,
        )
        observe(camera)
        // A bind while the engine runs attaches the use cases at once; the first bind of a start waits
        // for the camera to open, which applies them.
        if (registry.currentState == Lifecycle.State.RESUMED) hardwareControls.applyDesiredHardwareState(camera)
        return null
    }

    /**
     * Rebinds the bound camera with VideoCapture in the group, its main-thread part one
     * `CameraK.videoBind` interval (B1 records it). When CameraX refuses the group, the photo session
     * is bound again without VideoCapture and the error is returned. Main thread.
     */
    private fun bindWithVideo(): CameraKError? {
        val live = session ?: return CameraKError.NotReady
        val camera = boundCamera ?: return CameraKError.NotReady
        val binding = provider ?: return CameraKError.NotReady
        val error = tracer.beginInterval(VIDEO_BIND_TRACE).use { bind(camera, binding) } ?: return null
        videoRecorder.dropVideoCapture()
        bind(camera, binding)?.let { rollback ->
            fail<Unit>(live, rollback)
            return rollback
        }
        return error
    }

    // The camera's state, torch and zoom, for as long as it is the bound one.
    private fun observe(camera: Camera) {
        val info = camera.cameraInfo
        observeOn(camera, info.cameraState, "camera state") { onCameraState(camera, it) }
        observeOn(camera, info.torchState, "torch") { torch ->
            publishHardware(torchMode = if (torch == TorchState.ON) TorchMode.ON else TorchMode.OFF)
        }
        observeOn(camera, info.zoomState, "zoom") { zoom ->
            _zoom.value = ZoomState(zoom.zoomRatio, zoom.minZoomRatio, zoom.maxZoomRatio)
        }
    }

    private fun <T> observeOn(camera: Camera, data: LiveData<T>, report: String, onValue: (T) -> Unit) {
        val observer = Observer<T> { value ->
            if (closed.value || camera !== binder.camera) return@Observer
            try {
                onValue(value)
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "Handling a $report report failed", e)
            }
        }
        observers += { data.removeObserver(observer) }
        data.observeForever(observer)
    }

    private fun stopObserving() {
        observers.forEach { it() }
        observers.clear()
    }

    private fun publishHardware(
        flashMode: FlashMode = _hardware.value.flashMode,
        torchMode: TorchMode = _hardware.value.torchMode,
        targetOrientation: DeviceOrientation? = _hardware.value.targetOrientation,
    ) {
        _hardware.value = HardwareState(flashMode, torchMode, _hardware.value.camera, targetOrientation)
    }

    private fun onCameraState(camera: Camera, value: CameraState) {
        if (closed.value || camera !== binder.camera) return
        val live = session ?: return
        if (live.failed) return
        when (val signal = CameraXStateMapper.map(value, bindingVideo = videoRecorder.videoCapture != null)) {
            CameraXSignal.Open -> {
                live.recovery?.cancel()
                live.recovery = null
                val opened = _state.value != EngineState.Open
                _state.value = EngineState.Open
                if (opened) {
                    // The camera's control is active from here: apply what is desired and report it.
                    hardwareControls.applyDesiredHardwareState(camera)
                    publishHardware(
                        flashMode = hardwareControls.boundFlashMode() ?: _hardware.value.flashMode,
                        targetOrientation = orientation.targetOrientation,
                    )
                }
                accept(live)
            }
            is CameraXSignal.Interrupted -> {
                _state.value = EngineState.Interrupted(signal.reason)
                accept(live)
                if (live.recovery == null) {
                    live.recovery = scope.launch {
                        delay(RECOVERY_TIMEOUT)
                        if (session === live && !live.failed) fail<Unit>(live, CameraKError.Hardware(signal.code))
                    }
                }
            }
            is CameraXSignal.Failed -> fail<Unit>(live, signal.error)
            CameraXSignal.None -> Unit
        }
    }

    // CameraX accepted the start: the session is open or held. The start returns here.
    private fun accept(live: Session) {
        live.startTrace?.close()
        live.startTrace = null
        live.opened.complete(CameraKResult.Success(Unit))
    }

    /** Ends [live]'s generation with [error]: published before any start waiting on it returns. */
    private fun <T> fail(live: Session, error: CameraKError): CameraKResult<T> {
        if (session === live && !live.failed) {
            live.failed = true
            live.recovery?.cancel()
            live.recovery = null
            live.startTrace?.close()
            live.startTrace = null
            _state.value = EngineState.Failed(error)
            live.opened.complete(CameraKResult.Failure(error))
        }
        return CameraKResult.Failure(error)
    }

    // Ends [live]: the camera closes (use cases stay bound), the sensor stops, Closed is published.
    private fun endSession(live: Session) {
        session = null
        live.recovery?.cancel()
        live.opened.complete(CLOSED)
        live.startTrace?.close()
        live.firstFrameTrace?.close()
        live.startTrace = null
        live.firstFrameTrace = null
        awaitingFirstFrame.value = false
        orientation.stop()
        binder.stopListeningForDisplayChanges()
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            registry.currentState = Lifecycle.State.CREATED
        }
        _state.value = EngineState.Closed
    }

    // Waits for [deferred] unless the session ends first; null when it ended.
    private suspend fun <T> awaitUnlessEnded(live: Session, deferred: Deferred<T>): T? = select {
        deferred.onAwait { it }
        live.opened.onAwait { null }
    }

    private fun providerAsync(): Deferred<Result<CameraBinding>> {
        provider?.let { return CompletableDeferred(Result.success(it)) }
        return scope.async(mainImmediate) {
            suspendCancellableCoroutine { continuation ->
                backend.requestProvider(context, mainExecutor) { result -> continuation.resume(result) }
            }.onSuccess { provider = it }
        }
    }

    private fun onDeviceOrientation(orientation: DeviceOrientation) {
        if (session == null) return
        _deviceOrientation.value = orientation
    }

    // On a camera thread, for every frame of the preview.
    private fun onPreviewFrame() {
        if (!awaitingFirstFrame.compareAndSet(true, false)) return
        onMain {
            session?.let {
                it.firstFrameTrace?.close()
                it.firstFrameTrace = null
            }
        }
    }

    // ── Display and rotation ───────────────────────────────────────────────────────────────────

    private fun onDisplayChanged() {
        if (closed.value || binder.camera == null) return
        // Auto mode only; an explicit target orientation stays as set.
        if (orientation.targetOrientation == null) applyCaptureRotation()
        rebindIfViewPortOrientationChanged()
    }

    private fun viewPortStale(): Boolean =
        boundCamera != null && !videoRecorder.isRecording && binder.viewPortOrientationChanged()

    /**
     * Rebinds the camera when the display flips between portrait and landscape or its rotation turns
     * a quarter, so the (immutable) ViewPort is rebuilt with the matching Rational and rotation.
     */
    internal fun rebindIfViewPortOrientationChanged() {
        if (binder.camera == null || !binder.viewPortOrientationChanged()) return
        if (videoRecorder.isRecording) {
            // Unbinding to rebind would tear down the VideoCapture session; the recording's Finalize
            // performs the rebind once it is safe.
            pendingViewPortRebind = true
            return
        }
        scope.launch(mainImmediate) { rebind() }
    }

    /**
     * Sets the capture rotation from the current display rotation (auto mode) or the explicit target
     * orientation. Keeping capture rotation tied to the display — the same source as the ViewPort and
     * the preview box — guarantees the saved photo's orientation matches what the user saw (#136).
     */
    internal fun applyCaptureRotation() {
        val override = orientation.targetOrientation
        applyRotation(override?.toSurfaceRotation() ?: binder.displayRotation())
    }

    internal fun applyRotation(rotation: Int) {
        stillCapturer.imageCapture?.targetRotation = rotation
        videoRecorder.videoCapture?.targetRotation = rotation
    }

    /** Mirrors the saved image for the front camera when configured, so it matches the preview (#112). */
    private fun captureMetadata(): ImageCapture.Metadata = ImageCapture.Metadata().apply {
        isReversedHorizontal = config.mirrorFrontCamera && boundLens == CameraLens.FRONT
    }

    private inner class VideoHost : VideoSessionHost {
        override val isReleased: Boolean get() = closed.value

        override val isBound: Boolean get() = this@AndroidCameraEngine.isBound

        override fun bindWithVideo(): CameraKError? = this@AndroidCameraEngine.bindWithVideo()

        override suspend fun videoQualities(): Set<VideoQuality> = catalog.videoQualities()

        // Dispatched, never run inline: a caller that gives up before the bind runs leaves nothing bound.
        override suspend fun rebindAndAwait() {
            withTimeoutOrNull(STOP_FINALIZE_TIMEOUT_MS) { withContext(main) { rebind() } }
        }

        override fun rebindAfterRecordingEndedByCameraX() {
            pendingViewPortRebind = false
            scope.launch(mainImmediate) { rebind() }
        }

        // Applies any rotation flip that was deferred while recording was in progress, then binds an
        // ImageAnalysis a registration made during the recording asked for, unless that rebind or the
        // recording's own included it.
        override fun onNoActiveRecording() {
            if (pendingViewPortRebind && !closed.value) {
                pendingViewPortRebind = false
                rebindIfViewPortOrientationChanged()
            }
            if (framePipeline.analysisMissing) scope.launch(mainImmediate) { bindAnalysisOrApplyConsumers() }
        }
    }

    // ── Close ──────────────────────────────────────────────────────────────────────────────────

    // Main thread. Releases everything once: a recording first, finalized while the camera is still
    // open (§2.3 rule 7), then the sensor and frame delivery, the registry and this engine's use
    // cases, then what the 1.x callers were waiting on. A step that throws is logged and the steps
    // after it still run.
    private suspend fun release() {
        try {
            videoRecorder.stopForShutdown()
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Stopping the recording on release failed", e)
        }
        releaseStep("Ending the session") { session?.let(::endSession) }
        releaseStep("Removing the camera observers") { stopObserving() }
        releaseStep("Releasing the orientation sensor") { orientation.release() }
        releaseStep("Removing the display listener") { binder.stopListeningForDisplayChanges() }
        // Stop frame delivery before anything it uses goes away, and stop a recording while its
        // VideoCapture is still bound so it finalizes cleanly.
        releaseStep("Stopping frame delivery on release") { framePipeline.stopDelivery() }
        releaseStep("Destroying the engine lifecycle") {
            if (registry.currentState == Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.CREATED
            registry.currentState = Lifecycle.State.DESTROYED
        }
        releaseStep("Unbinding use cases on release") { binder.unbindOwnUseCases() }
        releaseStep("Dropping the frame consumers") { framePipeline.clearConsumers() }
        // A capture still in flight would otherwise wait for a callback that is now ignored.
        releaseStep("Failing the captures in flight") { stillCapturer.failInFlight() }
        releaseStep("Shutting down the image-processing executor") { imageProcessingExecutor.shutdown() }
        releaseStep("Shutting down the analyzer executor") { analyzerExecutor.shutdown() }
        scope.cancel()
    }

    private inline fun releaseStep(operation: String, step: () -> Unit) {
        try {
            step()
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "$operation failed", e)
        }
    }

    internal companion object {
        /** How long CameraX may take to reopen after a recoverable error before the session fails. */
        val RECOVERY_TIMEOUT: Duration = 10.seconds

        /** The main-thread part of a VideoCapture bind, the Pixel 7 rule's device marker (§2.4). */
        const val VIDEO_BIND_TRACE = "CameraK.videoBind"

        private val CLOSED = CameraKResult.Failure(CameraKError.Closed)
        private val NOT_READY = CameraKResult.Failure(CameraKError.NotReady)

        private fun immediateFormOf(dispatcher: CoroutineDispatcher): CoroutineDispatcher {
            val main = dispatcher as? MainCoroutineDispatcher ?: return dispatcher
            return try {
                main.immediate
            } catch (e: UnsupportedOperationException) {
                CameraKLogger.w(TAG, "The main dispatcher has no immediate form; dispatching every call", e)
                main
            }
        }

        fun cameraKThreads(name: String): ThreadFactory {
            val count = atomic(0)
            return ThreadFactory { runnable ->
                Thread(runnable, "$name-${count.incrementAndGet()}").apply { isDaemon = true }
            }
        }
    }
}

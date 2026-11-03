@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
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
import com.kashif.cameraK.engine.InactiveFrameSource
import com.kashif.cameraK.engine.LegacyControllerAccess
import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.engine.TraceNames
import com.kashif.cameraK.engine.Tracer
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.TorchMode
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
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
 * The 1.x [CameraController] is a facade over this engine ([legacyController]); its members that act
 * on what is bound run on the collaborators below, as in 1.x, until A2, A3' and A4' give the engine
 * those commands. Until then [setZoom], [setTorchMode], [setTargetOrientation], [capture] and the
 * recording calls are refused with `InvalidState`, each kind logged once.
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
    LifecycleOwner,
    LegacyControllerAccess {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val main: CoroutineDispatcher = environment.dispatchers.main

    // Runs at once when already on the main thread, so a 1.x call made there takes effect before it
    // returns, as it did in 1.x. A test dispatcher has no immediate form and is used as it is.
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

    /** True from [close] on; the collaborators read it as the 1.x controller's `released`. */
    internal val isClosed: Boolean get() = closed.value

    private val _state = MutableStateFlow<EngineState>(EngineState.Closed)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _hardware = MutableStateFlow(HardwareState(FlashMode.OFF, TorchMode.OFF, null, null))
    override val hardware: StateFlow<HardwareState> = _hardware.asStateFlow()

    // Zoom read-back from cameraInfo.zoomState comes with A2.
    private val _zoom = MutableStateFlow(ZoomState(1f, 1f, 1f))
    override val zoom: StateFlow<ZoomState> = _zoom.asStateFlow()

    private val _deviceOrientation = MutableStateFlow(DeviceOrientation.PORTRAIT)
    override val deviceOrientation: StateFlow<DeviceOrientation> = _deviceOrientation.asStateFlow()

    private val _capabilities = MutableStateFlow(CameraCapabilities.EMPTY)
    override val capabilities: StateFlow<CameraCapabilities> = _capabilities.asStateFlow()

    // The engine's own recording milestones and frame source come with A3' and A4'; until then the
    // 1.x facade records and feeds analyzers through the collaborators below.
    override val recordingEvents: Flow<EngineRecordingEvent> = emptyFlow()
    override val frames: FrameSource = InactiveFrameSource()

    private val previewSource = EnginePreviewSource(onMain = ::onMain, onDisplayAttached = ::onDisplayChanged)
    override val preview: PlatformPreviewSource get() = previewSource

    // CameraX's own callbacks (the provider, frame-analysis and capture results the engine reads) run
    // on the main dispatcher.
    private val mainExecutor = main.asExecutor()

    internal val selectorResolver = CameraSelectorResolver(lensSource)
    internal val orientation = OrientationTracker(context)
    internal val binder = UseCaseBinder(
        context = context,
        lifecycleOwner = this,
        aspectRatio = config.aspectRatio,
        targetResolution = config.targetResolution,
        isReleased = { closed.value },
        display = { previewSource.display },
        onDisplayChanged = ::onDisplayChanged,
    )

    // CameraX's file output always encodes JPEG, so that is what every capture file holds, whatever
    // imageFormat asks for; its name and MIME type follow the bytes.
    private val outputFormat = ImageFormat.JPEG
    private val files = OutputFileFactory(context, config.directory, outputFormat)
    internal val stillCapturer = StillCapturer(
        context = context,
        backend = backend,
        files = files,
        imageProcessingExecutor = imageProcessingExecutor,
        qualityPriority = config.qualityPrioritization,
        isReleased = { closed.value },
        metadata = ::captureMetadata,
    )
    internal val hardwareControls = HardwareControls(
        context = context,
        flashMode = config.flashMode,
        torchMode = config.torchMode,
        camera = { binder.camera },
        imageCapture = { stillCapturer.imageCapture },
    )
    internal val framePipeline = FramePipeline(backend, analyzerExecutor)
    internal val videoRecorder = VideoRecorder(context, backend, files, VideoHost())

    /** The physical lens every use case of the current bind pins, or null. */
    internal var pinnedPhysicalId: String? = null
        private set

    /** The facing of the current bind; the facade's front-camera mirroring reads it. */
    internal var boundLens: CameraLens = config.cameraLens
        private set

    override val legacyController: CameraController by lazy { CameraController(this, hostLifecycle = null) }

    // Called by the facade's 1.x orientation listener, on the main thread, while the sensor runs.
    internal var onOrientationChanged: ((DeviceOrientation) -> Unit)? = null

    private val enumeration: Deferred<CameraCapabilities> = scope.async(environment.dispatchers.default) {
        androidCapabilities(enumerateLenses()).also { _capabilities.value = it }
    }

    private var provider: CameraBinding? = null

    // Main thread only, as everything below: the live session, the camera it is bound on, and the
    // observer of that camera's state.
    private var session: Session? = null
    private var boundCamera: ResolvedCamera? = null
    private var observed: Pair<LiveData<CameraState>, Observer<CameraState>>? = null

    // The facade's onCameraReady callbacks, run once the next bind has a camera.
    private val bindListeners = mutableListOf<() -> Unit>()

    // Set when a portrait↔landscape flip is detected during recording (when rebinding would tear
    // down the recording). The deferred rebind runs once the recording finalizes.
    private var pendingViewPortRebind = false

    private var teardown: Job? = null
    private val refusedOperations = atomic(emptySet<CameraOperation>())
    private val awaitingFirstFrame = AtomicBoolean(false)

    init {
        // Declared up front, so the first bind includes the ImageAnalysis and a plugin that registers
        // later only joins it (Decision 14).
        if (config.frameAnalysis) framePipeline.requestAnalysis()
        if (config.imageFormat != outputFormat && config.imageFormat != ImageFormat.HEIC) {
            CameraKLogger.w(
                TAG,
                "ImageFormat.${config.imageFormat} is not supported for Android captures; saving JPEG instead",
            )
        }
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
        live.startTrace = tracer.beginInterval(TraceNames.START)
        live.firstFrameTrace = tracer.beginInterval(TraceNames.FIRST_FRAME)
        awaitingFirstFrame.set(true)
        applyDesired(desired)
        try {
            // A stop or a close completes `opened` while the start waits, which ends the wait.
            if (awaitUnlessEnded(live, enumeration) == null) return live.opened.await()
            val resolved = resolve(desired.selection)
                ?: return fail(live, CameraKError.NoCamera(lensOf(desired.selection)))
            // The display listener runs only while started, so a display that turned while stopped
            // left the ViewPort stale: the start rebinds then, even on the same camera.
            if (resolved != boundCamera || viewPortStale()) {
                val bindProvider = awaitUnlessEnded(live, providerAsync()) ?: return live.opened.await()
                val error = bindProvider.fold(
                    onSuccess = { bind(resolved, it) },
                    onFailure = {
                        CameraKLogger.e(TAG, "Obtaining the camera provider failed", it)
                        CameraKError.Internal(it)
                    },
                )
                if (error != null) return fail(live, error)
            } else {
                // A half turn keeps the ViewPort but not the capture rotation.
                if (orientation.targetOrientation == null) applyCaptureRotation()
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
                else -> switchTo(live, selection)
            }
        }
    }

    private suspend fun switchTo(live: Session, selection: CameraSelection): CameraKResult<LensInfo> {
        enumeration.await()
        val resolved = resolve(selection)
            ?: return CameraKResult.Failure(CameraKError.NoCamera(lensOf(selection)))
        if (resolved != boundCamera) {
            val error = bind(resolved, checkNotNull(provider) { "a live session has its provider" })
            if (error != null) {
                return fail(live, error)
            }
        }
        val camera = _hardware.value.camera ?: return CameraKResult.Failure(
            CameraKError.Internal(IllegalStateException("the bound camera is not one the capabilities list")),
        )
        return CameraKResult.Success(camera)
    }

    override suspend fun setFlashMode(mode: FlashMode): CameraKResult<FlashMode> {
        if (closed.value) return CLOSED
        return withContext(mainImmediate) {
            val live = session
            if (live == null || live.failed) {
                CameraKResult.Failure(CameraKError.NotReady)
            } else {
                // The flash is a setting of the bound ImageCapture; hardware reports it from the next bind.
                hardwareControls.setFlashMode(mode)
                CameraKResult.Success(mode)
            }
        }
    }

    override suspend fun capture(): CameraKResult<CapturedImage> = refuse(CameraOperation.CAPTURE)

    override suspend fun setZoom(ratio: Float): CameraKResult<ZoomState> = refuse(CameraOperation.ZOOM)

    override suspend fun setTorchMode(mode: TorchMode): CameraKResult<TorchMode> = refuse(CameraOperation.TORCH)

    override suspend fun setTargetOrientation(orientation: DeviceOrientation?): CameraKResult<Unit> =
        refuse(CameraOperation.ORIENTATION)

    override suspend fun prepareRecording(config: VideoConfiguration): CameraKResult<Unit> =
        refuse(CameraOperation.RECORD_PREPARE)

    override suspend fun startRecording(config: VideoConfiguration): CameraKResult<String> =
        refuse(CameraOperation.RECORD_START)

    override suspend fun stopRecording(): CameraKResult<RecordedVideo> = refuse(CameraOperation.RECORD_STOP)

    override suspend fun pauseRecording(): CameraKResult<Unit> = refuse(CameraOperation.RECORD_PAUSE)

    override suspend fun resumeRecording(): CameraKResult<Unit> = refuse(CameraOperation.RECORD_RESUME)

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

    /** Runs [block] once the camera is bound: at once when it is, else after the next bind. Main thread. */
    internal fun whenBound(block: () -> Unit) {
        if (binder.camera != null) runCameraReady(block) else bindListeners += block
    }

    // The facade's onCameraReady is app code: one that throws is logged, as 1.x did, and goes no further.
    private fun runCameraReady(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "An onCameraReady callback failed", e)
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

    /** The camera for the facade's lens and device type, as the 1.x bind resolved it. */
    internal fun boundCameraOr(lens: CameraLens, deviceType: CameraDeviceType): ResolvedCamera =
        boundCamera ?: selectorResolver.resolve(lens, deviceType)

    /** Unbinds this engine's use cases, as the 1.x stopSession did; the next start binds again. Main thread. */
    internal fun unbindOwnUseCases() {
        stopObserving()
        binder.unbindOwnUseCases()
        boundCamera = null
    }

    // ── Session ────────────────────────────────────────────────────────────────────────────────

    private fun applyDesired(desired: DesiredHardware) {
        hardwareControls.setFlashMode(desired.flashMode)
        hardwareControls.setTorchMode(desired.torchMode)
        // Every bind gives CameraX a camera at 1x, so 1x needs no request.
        if (desired.zoomRatio != 1f) hardwareControls.setZoom(desired.zoomRatio)
        orientation.targetOrientation = desired.targetOrientation
    }

    private fun resolve(selection: CameraSelection): ResolvedCamera? = when (selection) {
        is CameraSelection.ByLens -> selectorResolver.resolveFacing(selection.lens, selection.deviceType)
        is CameraSelection.ById -> selectorResolver.resolveId(selection.id)
    }

    private fun lensOf(selection: CameraSelection): CameraLens? = (selection as? CameraSelection.ByLens)?.lens

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
        // VideoCapture is built only while a recording needs it.
        videoRecorder.configureForBind()
        // Rebuilt rather than reused: its Camera2Interop pin belongs to the bind it was built for.
        framePipeline.analysis = if (framePipeline.analysisRequested) framePipeline.build(pinned) else null
        applyCaptureRotation()

        val useCases = buildList<UseCase> {
            add(preview)
            add(checkNotNull(stillCapturer.imageCapture))
            videoRecorder.useCaseForBind()?.let { add(it) }
            framePipeline.analysis?.let { add(it) }
        }
        val camera = try {
            binder.bindGroup(resolved, useCases)
        } catch (e: SecurityException) {
            CameraKLogger.e(TAG, "Binding the camera was refused", e)
            return CameraKError.Permission(
                CameraKPermission.CAMERA,
                PermissionStatus.Denied(shouldShowRationale = false),
                e,
            )
        } catch (e: IllegalArgumentException) {
            CameraKLogger.e(TAG, "No camera matches the selection", e)
            return CameraKError.NoCamera(resolved.lens, e)
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Binding the use cases failed", e)
            return CameraKError.Internal(e)
        }
        boundCamera = resolved
        observe(camera)
        hardwareControls.applyDesiredHardwareState(camera)
        _hardware.value = HardwareState(
            flashMode = hardwareControls.boundFlashMode() ?: hardwareControls.flashMode,
            torchMode = TorchMode.OFF,
            camera = reportedLens(resolved, camera, binding),
            targetOrientation = orientation.targetOrientation,
        )
        val ready = bindListeners.toList()
        bindListeners.clear()
        ready.forEach(::runCameraReady)
        return null
    }

    // The bound camera's id as CameraX reports it, or the pinned physical lens inside it.
    private fun reportedLens(resolved: ResolvedCamera, camera: Camera, binding: CameraBinding): LensInfo? {
        val id = (resolved as? ResolvedCamera.PhysicalLens)?.physicalId ?: try {
            binding.cameraIdOf(camera)
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Reading the bound camera's id failed", e)
            return null
        }
        return _capabilities.value.allLenses.firstOrNull { it.id == id }
    }

    private fun observe(camera: Camera) {
        val data = camera.cameraInfo.cameraState
        val observer = Observer<CameraState> { value ->
            try {
                onCameraState(camera, value)
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "Handling a camera state report failed", e)
            }
        }
        observed = data to observer
        data.observeForever(observer)
    }

    private fun stopObserving() {
        observed?.let { (data, observer) -> data.removeObserver(observer) }
        observed = null
    }

    private fun onCameraState(camera: Camera, value: CameraState) {
        if (closed.value || camera !== binder.camera) return
        val live = session ?: return
        if (live.failed) return
        when (val signal = CameraXStateMapper.map(value, bindingVideo = videoRecorder.videoCapture != null)) {
            CameraXSignal.Open -> {
                live.recovery?.cancel()
                live.recovery = null
                _state.value = EngineState.Open
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
        awaitingFirstFrame.set(false)
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

    private fun enumerateLenses(): List<LensInfo> = try {
        selectorResolver.lensSnapshot().map { it.info }
    } catch (e: Exception) {
        CameraKLogger.e(TAG, "Enumerating the cameras failed", e)
        emptyList()
    }

    private fun onDeviceOrientation(orientation: DeviceOrientation) {
        if (session == null) return
        _deviceOrientation.value = orientation
        onOrientationChanged?.invoke(orientation)
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
     * Rebinds the camera when the display rotation flips between portrait and landscape, so the
     * (immutable) ViewPort is rebuilt with the matching Rational.
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

        // Dispatched, never run inline: a caller that gives up before the bind runs leaves nothing bound.
        override suspend fun rebindAndAwait() {
            withTimeoutOrNull(STOP_FINALIZE_TIMEOUT_MS) { withContext(main) { rebind() } }
        }

        override fun rebindAfterRecordingEndedByCameraX() {
            pendingViewPortRebind = false
            scope.launch(mainImmediate) { rebind() }
        }

        // Applies any rotation flip that was deferred while recording was in progress.
        override fun onNoActiveRecording() {
            if (pendingViewPortRebind && !closed.value) {
                pendingViewPortRebind = false
                rebindIfViewPortOrientationChanged()
            }
        }
    }

    // ── Close ──────────────────────────────────────────────────────────────────────────────────

    // Main thread. Releases everything once: the sensor, frame delivery and a recording first, then
    // the registry and this engine's use cases, then what the 1.x callers were waiting on. A step that
    // throws is logged and the steps after it still run.
    private fun release() {
        releaseStep("Ending the session") { session?.let(::endSession) }
        releaseStep("Removing the camera observers") { stopObserving() }
        releaseStep("Releasing the orientation sensor") { orientation.release() }
        releaseStep("Removing the display listener") { binder.stopListeningForDisplayChanges() }
        // Stop frame delivery before anything it uses goes away, and stop a recording while its
        // VideoCapture is still bound so it finalizes cleanly.
        releaseStep("Stopping frame delivery on release") { framePipeline.stopDelivery() }
        releaseStep("Stopping the recording on release") { videoRecorder.stopForRelease() }
        releaseStep("Destroying the engine lifecycle") {
            if (registry.currentState == Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.CREATED
            registry.currentState = Lifecycle.State.DESTROYED
        }
        releaseStep("Unbinding use cases on release") { binder.unbindOwnUseCases() }
        releaseStep("Dropping the frame consumers") { framePipeline.clearConsumers() }
        releaseStep("Dropping the capture listeners") { stillCapturer.clearListeners() }
        releaseStep("Failing the unclaimed recording outcome") { videoRecorder.failUnclaimedOutcome() }
        // A capture still in flight would otherwise wait for a callback that is now ignored.
        releaseStep("Failing the captures in flight") { stillCapturer.failInFlight() }
        bindListeners.clear()
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

    private fun <T> refuse(operation: CameraOperation): CameraKResult<T> {
        if (closed.value) return CLOSED
        if (operation !in refusedOperations.getAndUpdate { it + operation }) {
            CameraKLogger.w(
                TAG,
                "The Android engine refuses the $operation command until its slice lands; issue it on Ready.controller",
            )
        }
        return CameraKResult.Failure(CameraKError.InvalidState(operation))
    }

    internal companion object {
        /** How long CameraX may take to reopen after a recoverable error before the session fails. */
        val RECOVERY_TIMEOUT: Duration = 10.seconds

        private val CLOSED = CameraKResult.Failure(CameraKError.Closed)

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
            val count = AtomicInteger()
            return ThreadFactory { runnable ->
                Thread(runnable, "$name-${count.incrementAndGet()}").apply { isDaemon = true }
            }
        }
    }
}

/**
 * The capabilities the engine reports for [lenses] until each member's slice fills it (design §6.3):
 * the 1.x shape's values, with pause and resume supported, which CameraX's Recorder does.
 */
@OptIn(CameraKEngineApi::class)
internal fun androidCapabilities(lenses: List<LensInfo>): CameraCapabilities {
    val legacy = CameraCapabilities(lenses)
    return CameraCapabilities(
        allLenses = lenses,
        flashModes = lenses.associate { it.id to legacy.supportedFlashModes(it.id) },
        torchModes = lenses.associate { it.id to legacy.supportedTorchModes(it.id) },
        supportsPauseResume = true,
        supportedVideoQualities = legacy.supportedVideoQualities,
        supportsVideoBitrate = legacy.supportsVideoBitrate,
        supportedAspectRatios = legacy.supportedAspectRatios,
        supportedImageFormats = legacy.supportedImageFormats,
        supportedAnalysisFormats = legacy.supportedAnalysisFormats,
        supportsSquareCapture = legacy.supportsSquareCapture,
    )
}

@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraState
import androidx.camera.core.UseCase
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.TraceNames
import com.kashif.cameraK.engine.Tracer
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.permissions.CameraKPermission
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.ZoomState
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

private const val TAG = "CameraK"

/**
 * [AndroidCameraEngine]'s camera session (design §2.4): the lifecycle its use cases are bound to,
 * one generation per start, the bind, and the observers of the bound camera. [start] moves
 * [registry] to RESUMED, [stop] back to CREATED with the use cases still bound, so the next start
 * reopens the camera without a rebind, and the engine's release to DESTROYED.
 *
 * [state] comes from CameraX's `cameraInfo.cameraState`, observed on every camera a bind returns and
 * mapped by [CameraXStateMapper]: OPEN is Open, a recoverable error is Interrupted until CameraX
 * reopens, or Failed after [AndroidCameraEngine.RECOVERY_TIMEOUT], and a critical error is Failed. A
 * report reaches the state only while its session is live, so nothing a stopped, closed or failed
 * session's camera says changes it. Main thread, except [markClosed].
 */
internal class EngineSession(
    owner: LifecycleOwner,
    private val config: CameraConfiguration,
    private val context: Context,
    private val backend: CameraXBackend,
    private val scope: CoroutineScope,
    private val mainImmediate: CoroutineDispatcher,
    private val mainExecutor: Executor,
    private val tracer: Tracer,
    private val isReleased: () -> Boolean,
    private val files: OutputFileFactory,
    private val orientation: OrientationTracker,
    private val binder: UseCaseBinder,
    private val previewSource: EnginePreviewSource,
    private val stillCapturer: StillCapturer,
    private val framePipeline: FramePipeline,
    private val videoRecorder: VideoRecorder,
    private val catalog: LensCatalog,
    private val controls: EngineControls,
) {
    val registry = LifecycleRegistry(owner)

    private val _state = MutableStateFlow<EngineState>(EngineState.Closed)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** The physical lens every use case of the current bind pins, or null. */
    var pinnedPhysicalId: String? = null
        private set

    /** The facing of the current bind; the still capturer's front-camera mirroring reads it. */
    var boundLens: CameraLens = config.cameraLens ?: CameraLens.BACK
        private set

    private var provider: CameraBinding? = null

    // Main thread only, as everything below: the live session, the camera it is bound on, and the
    // observers of that camera's state, torch and zoom.
    private var session: Session? = null
    private var boundCamera: ResolvedCamera? = null
    private val observers = BoundCameraObservers { camera -> !isReleased() && camera === binder.camera }

    private val awaitingFirstFrame = atomic(false)

    /** One start's generation: every platform report checks it is still the live one before it acts. */
    class Session {
        /** Success once CameraX accepted the start (open or interrupted), or the failure that ended it. */
        val opened = CompletableDeferred<CameraKResult<Unit>>()
        var failed = false
        var recovery: Job? = null
        var startTrace: AutoCloseable? = null
        var firstFrameTrace: AutoCloseable? = null
    }

    /** What a rebind binds again: the live session's camera, on its provider. */
    class BindTarget(val live: Session, val camera: ResolvedCamera, val binding: CameraBinding)

    /** Whether the live session's camera is open, the only state that takes hardware commands. */
    val isOpen: Boolean get() = session?.failed == false && _state.value == EngineState.Open

    /** Whether a bind has given the session its camera. */
    val isBound: Boolean get() = binder.camera != null

    /** Whether a start has made a session that no stop or close has ended. */
    val isLive: Boolean get() = session != null

    suspend fun start(desired: DesiredHardware): CameraKResult<Unit> {
        if (isReleased()) return AndroidCameraEngine.CLOSED
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
        controls.store(desired)
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
                controls.applyCaptureRotation()
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

    /** Ends the live session, or publishes Closed when there is none. */
    fun stop() {
        val live = session
        if (live != null) endSession(live) else _state.value = EngineState.Closed
    }

    suspend fun select(selection: CameraSelection): CameraKResult<LensInfo> {
        // A switch asked for while the start is in flight runs once CameraX has accepted it.
        session?.takeIf { !it.failed }?.opened?.let { opened ->
            val started = opened.await()
            if (started is CameraKResult.Failure) return started
        }
        val live = session
        return when {
            isReleased() -> AndroidCameraEngine.CLOSED
            live == null || live.failed -> CameraKResult.Failure(CameraKError.NotReady)
            videoRecorder.isRecording -> {
                CameraKLogger.w(TAG, "Ignoring lens switch while recording")
                CameraKResult.Failure(CameraKError.Busy(CameraOperation.LENS))
            }
            else -> tracer.beginInterval(TraceNames.LENS_SWITCH).use { switchTo(live, selection) }
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
        val camera = controls.hardware.value.camera ?: return CameraKResult.Failure(
            CameraKError.Internal(IllegalStateException("the bound camera is not one the capabilities list")),
        )
        return CameraKResult.Success(camera)
    }

    /** The live session's bound camera and its provider, or null before a bind. */
    fun bindTarget(): BindTarget? {
        val live = session ?: return null
        val camera = boundCamera ?: return null
        val binding = provider ?: return null
        return BindTarget(live, camera, binding)
    }

    /** Binds [target]'s camera again with the current use cases; null when bound, else the error. */
    fun bind(target: BindTarget): CameraKError? = bind(target.camera, target.binding)

    /** Ends [target]'s session with [error], as a failed bind does. */
    fun fail(target: BindTarget, error: CameraKError) {
        fail<Unit>(target.live, error)
    }

    /** Rebinds the bound camera with the current use cases, if a session has one. */
    suspend fun rebind() {
        val live = session ?: return
        val camera = boundCamera ?: return
        val error = bind(camera, provider ?: return)
        if (error != null) fail<Unit>(live, error)
    }

    /** Binds the use cases on [resolved]; null when bound, else the error. */
    private fun bind(resolved: ResolvedCamera, binding: CameraBinding): CameraKError? {
        binder.useProvider(binding)
        observers.clear()
        boundCamera = null
        val resolutionSelector = binder.resolutionSelector()
        val pinned = resolved.pinnedPhysicalId
        pinnedPhysicalId = pinned
        boundLens = resolved.lens

        val preview = binder.buildPreview(resolutionSelector, pinned) { backend.onPreviewFrame(it, ::onPreviewFrame) }
        previewSource.bindTo(preview)
        stillCapturer.configure(resolutionSelector, controls.flashMode, pinned)
        // VideoCapture joins the group only while a recording needs it or it was prepared.
        val video = videoRecorder.useCaseForBind()
        catalog.forgetBoundQualities()
        // Rebuilt rather than reused: its Camera2Interop pin belongs to the bind it was built for.
        framePipeline.analysis = if (framePipeline.analysisRequested) framePipeline.build(pinned) else null
        controls.applyCaptureRotation()

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
        controls.onBound(camera) { catalog.describeBound(camera, catalog.reportedLens(resolved, camera, binding)) }
        observe(camera)
        // A bind while the engine runs attaches the use cases at once; the first bind of a start waits
        // for the camera to open, which applies them.
        if (registry.currentState == Lifecycle.State.RESUMED) controls.applyDesiredTo(camera)
        return null
    }

    // The camera's state, torch and zoom, for as long as it is the bound one.
    private fun observe(camera: Camera) {
        val info = camera.cameraInfo
        observers.observe(camera, info.cameraState, "camera state") { onCameraState(camera, it) }
        observers.observe(camera, info.torchState, "torch") { controls.reportTorch(it) }
        observers.observe(camera, info.zoomState, "zoom") { zoom ->
            controls.reportZoom(ZoomState(zoom.zoomRatio, zoom.minZoomRatio, zoom.maxZoomRatio))
        }
    }

    /** Removes the observers of the bound camera. */
    fun stopObserving() {
        observers.clear()
    }

    private fun onCameraState(camera: Camera, value: CameraState) {
        if (isReleased() || camera !== binder.camera) return
        val live = session ?: return
        if (live.failed) return
        when (val signal = CameraXStateMapper.map(value, bindingVideo = videoRecorder.videoCapture != null)) {
            CameraXSignal.Open -> {
                live.recovery?.cancel()
                live.recovery = null
                val opened = _state.value != EngineState.Open
                _state.value = EngineState.Open
                // The camera's control is active from here: apply what is desired and report it.
                if (opened) controls.onOpened(camera)
                accept(live)
            }
            is CameraXSignal.Interrupted -> {
                _state.value = EngineState.Interrupted(signal.reason)
                accept(live)
                if (live.recovery == null) {
                    live.recovery = scope.launch {
                        delay(AndroidCameraEngine.RECOVERY_TIMEOUT)
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

    /** Ends the live session, if there is one; the engine's release runs it. */
    fun endLive() {
        session?.let(::endSession)
    }

    // Ends [live]: the camera closes (use cases stay bound), the sensor stops, Closed is published.
    private fun endSession(live: Session) {
        session = null
        controls.onStopped()
        live.recovery?.cancel()
        live.opened.complete(AndroidCameraEngine.CLOSED)
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

    /** The engine closed, on any thread: a start waiting on the live session returns Closed. */
    fun markClosed() {
        session?.opened?.complete(AndroidCameraEngine.CLOSED)
        _state.value = EngineState.Closed
    }

    /** Moves the lifecycle to DESTROYED, through CREATED when it never left INITIALIZED. */
    fun destroyLifecycle() {
        if (registry.currentState == Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.CREATED
        registry.currentState = Lifecycle.State.DESTROYED
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

    // The display turned since the bound ViewPort was built, and no recording holds it.
    private fun viewPortStale(): Boolean =
        boundCamera != null && !videoRecorder.isRecording && binder.viewPortOrientationChanged()

    private fun onDeviceOrientation(orientation: DeviceOrientation) {
        if (session == null) return
        controls.reportDeviceOrientation(orientation)
    }

    // On a camera thread, for every frame of the preview.
    private fun onPreviewFrame() {
        if (!awaitingFirstFrame.compareAndSet(true, false)) return
        scope.launch(mainImmediate) {
            session?.let {
                it.firstFrameTrace?.close()
                it.firstFrameTrace = null
            }
        }
    }
}

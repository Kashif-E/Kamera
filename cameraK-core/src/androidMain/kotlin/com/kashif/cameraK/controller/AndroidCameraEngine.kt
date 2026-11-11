@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.kashif.cameraK.capabilities.AndroidLensDescriptor
import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensEnumerator
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.controls.NormalizedPoint
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineRecordingEvent
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.HardwareState
import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.engine.Tracer
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.ZoomState
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import com.kashif.cameraK.video.VideoQuality
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val TAG = "CameraK"

/**
 * The Android engine over CameraX (design §2.4). It is its own [LifecycleOwner]: [start] moves its
 * lifecycle to RESUMED, [stop] back to CREATED with its use cases still bound, so the next start
 * reopens the camera without a rebind, and [close] to DESTROYED. It binds and unbinds only its own
 * use cases. Every CameraX binding call, the lifecycle and every LiveData observer run on
 * `environment.dispatchers.main`.
 *
 * This class is the facade: it builds the collaborators, runs each command on the main thread in the
 * state it needs, and releases everything in order on [close]. [EngineSession] owns the lifecycle,
 * each start's generation, the bind and the state CameraX reports; [LensCatalog] the cameras and
 * their capabilities; [EngineControls] the hardware commands and what CameraX reports back;
 * [RecordingBinding] the binds a recording makes or holds back.
 *
 * Flash, torch, zoom, focus and exposure are commands for an open camera, NotReady otherwise; the
 * focus point and exposure index are [EngineControls]'s, through [FocusMetering]. The target
 * orientation is configuration, taken in any state but closed. The desired hardware is re-applied
 * once the camera opens and after every bind made while it runs.
 *
 * Stills and recordings are files (Decision 10): `outputDirectory`, used as given, else the app-owned
 * `filesDir/CameraK`; publishing to the gallery is the media saver plugin's job. [capture] returns the
 * written file, a real PNG for a PNG configuration; HEIC fails [start] with `Unsupported(IMAGE_FORMAT)`.
 * Each recording is observed from CameraX's Start to its Finalize on [recordingEvents] (§5);
 * VideoCapture is built off Main and bound only for a recording or from [prepareRecording] until the
 * engine stops (§2.4).
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
    override val lifecycle: Lifecycle get() = session.registry

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

    override val state: StateFlow<EngineState> get() = session.state

    override val hardware: StateFlow<HardwareState> get() = controls.hardware

    override val zoom: StateFlow<ZoomState> get() = controls.zoom

    override val deviceOrientation: StateFlow<DeviceOrientation> get() = controls.deviceOrientation

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
    internal val stillCapturer: StillCapturer = StillCapturer(
        backend = backend,
        files = files,
        imageProcessingExecutor = imageProcessingExecutor,
        callbackExecutor = mainExecutor,
        qualityPriority = config.qualityPrioritization,
        format = config.imageFormat,
        tracer = { tracer },
        isReleased = { closed.value },
        metadata = ::captureMetadata,
        boundLens = { session.boundLens },
        onExposureReported = { session, index -> controls.onExposureReported(session, index) },
    )
    internal val framePipeline = FramePipeline(
        backend = backend,
        analyzerExecutor = analyzerExecutor,
        sessionFormat = config.analysisFormat,
        newWorker = { name -> environment.runtime.newWorker(name) },
        resolutionSelector = { outputs -> binder.analysisResolutionSelector(config.analysisResolution, outputs) },
    )
    internal val videoRecorder: VideoRecorder =
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

    private val controls: EngineControls = EngineControls(
        context = context,
        flashMode = config.flashMode,
        torchMode = config.torchMode,
        backend = backend,
        orientation = orientation,
        binder = binder,
        stillCapturer = stillCapturer,
        videoRecorder = videoRecorder,
        preview = { previewSource.boundPreview },
        // CameraXViewfinder mirrors the front camera's preview.
        mirrored = { session.boundLens == CameraLens.FRONT },
        // CameraX describes the logical camera a pin binds; the pinned lens meters only what it can itself.
        pinnedMetering = { session.pinnedFacts?.metering },
        callbackExecutor = mainExecutor,
        isReleased = { closed.value },
    )

    private val catalog: LensCatalog = LensCatalog(
        lensSource = lensSource,
        backend = backend,
        scope = scope,
        background = environment.dispatchers.default,
        mainImmediate = mainImmediate,
        isReleased = { closed.value },
        boundCamera = { binder.camera },
    )

    private val session: EngineSession = EngineSession(
        owner = this,
        config = config,
        context = context,
        backend = backend,
        scope = scope,
        mainImmediate = mainImmediate,
        mainExecutor = mainExecutor,
        tracer = tracer,
        isReleased = { closed.value },
        files = files,
        orientation = orientation,
        binder = binder,
        previewSource = previewSource,
        stillCapturer = stillCapturer,
        framePipeline = framePipeline,
        videoRecorder = videoRecorder,
        catalog = catalog,
        controls = controls,
    )

    private val recordingBinding: RecordingBinding = RecordingBinding(
        session = session,
        controls = controls,
        orientation = orientation,
        binder = binder,
        framePipeline = framePipeline,
        videoRecorder = videoRecorder,
        tracer = tracer,
        scope = scope,
        main = main,
        mainImmediate = mainImmediate,
        isReleased = { closed.value },
    )

    private var teardown: Job? = null

    init {
        // Declared up front, so the first bind includes the ImageAnalysis and a plugin that registers
        // later only joins it (Decision 14).
        if (config.frameAnalysis) framePipeline.requestAnalysis()
        framePipeline.onConsumersChanged = { onMain(recordingBinding::bindAnalysisOrApplyConsumers) }
    }

    override suspend fun start(desired: DesiredHardware): CameraKResult<Unit> {
        if (closed.value) return CLOSED
        return withContext(mainImmediate) { session.start(desired) }
    }

    override suspend fun stop() {
        if (closed.value) return
        withContext(mainImmediate) {
            // A recording is finalized before the camera closes (§2.3 rule 7).
            withContext(NonCancellable) { videoRecorder.stopForShutdown() }
            session.stop()
        }
    }

    override suspend fun selectCamera(selection: CameraSelection): CameraKResult<LensInfo> {
        if (closed.value) return CLOSED
        return withContext(mainImmediate) { session.select(selection) }
    }

    override suspend fun setFlashMode(mode: FlashMode): CameraKResult<FlashMode> = openCommand {
        controls.setFlashMode(mode)
    }

    override suspend fun capture(): CameraKResult<CapturedImage> = openCommand { stillCapturer.captureImage() }

    override suspend fun setZoom(ratio: Float): CameraKResult<ZoomState> = openCommand { controls.setZoom(ratio) }

    override suspend fun setTorchMode(mode: TorchMode): CameraKResult<TorchMode> = openCommand {
        controls.setTorchMode(mode)
    }

    override suspend fun focusAt(point: NormalizedPoint): CameraKResult<Unit> = openCommand { controls.focusAt(point) }

    override suspend fun setExposureCompensation(index: Int): CameraKResult<Unit> = openCommand {
        controls.setExposureCompensation(index)
    }

    override suspend fun resetFocusAndExposure(): CameraKResult<Unit> = openCommand {
        controls.resetFocusAndExposure()
    }

    override suspend fun setTargetOrientation(orientation: DeviceOrientation?): CameraKResult<Unit> = unlessClosed {
        applyTargetOrientation(orientation)
        CameraKResult.Success(Unit)
    } ?: CLOSED

    // A command that needs the camera open (a hardware command, a capture, a recording's start): on
    // the main thread, NotReady otherwise.
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

    // A command taken in any state but closed (the target orientation, what is already recording):
    // on the main thread, null once closed.
    private suspend fun <T> unlessClosed(block: suspend () -> T): T? {
        if (closed.value) return null
        return withContext(mainImmediate) { if (closed.value) null else block() }
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

    override suspend fun stopRecording(): CameraKResult<RecordedVideo> = unlessClosed { videoRecorder.stop() } ?: CLOSED

    override suspend fun pauseRecording(): CameraKResult<Unit> = unlessClosed { videoRecorder.pause() } ?: CLOSED

    override suspend fun resumeRecording(): CameraKResult<Unit> = unlessClosed { videoRecorder.resume() } ?: CLOSED

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        session.markClosed()
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
    internal val isOpen: Boolean get() = session.isOpen

    /**
     * Sets the one target orientation captures follow, applies it to the bound outputs and, while
     * Open, reports it; before Open the next bind applies it and the open reports it. Main thread.
     */
    internal fun applyTargetOrientation(target: DeviceOrientation?) {
        if (closed.value) return
        controls.applyTargetOrientation(target, open = session.state.value == EngineState.Open)
    }

    private fun onDisplayChanged() {
        recordingBinding.onDisplayChanged()
    }

    /** Mirrors the saved image for the front camera when configured, so it matches the preview (#112). */
    private fun captureMetadata(): ImageCapture.Metadata = ImageCapture.Metadata().apply {
        isReversedHorizontal = config.mirrorFrontCamera && session.boundLens == CameraLens.FRONT
    }

    // VideoRecorder is built before the collaborators its host calls, so the host reads them per call.
    private inner class VideoHost : VideoSessionHost {
        override val isReleased: Boolean get() = closed.value

        override val isBound: Boolean get() = session.isBound

        override val pinnedPhysicalId: String? get() = session.pinnedPhysicalId

        override fun bindWithVideo(): CameraKError? = recordingBinding.bindWithVideo()

        override suspend fun videoQualities(): Set<VideoQuality> = catalog.videoQualities()

        override suspend fun rebindAndAwait() = recordingBinding.rebindAndAwait()

        override fun rebindAfterRecordingEndedByCameraX() = recordingBinding.rebindAfterRecordingEndedByCameraX()

        override fun onNoActiveRecording() = recordingBinding.onNoActiveRecording()
    }

    // ── Close ──────────────────────────────────────────────────────────────────────────────────

    // Main thread. Releases everything once: a recording first, finalized while the camera is still
    // open (§2.3 rule 7), then the sensor and frame delivery, the lifecycle and this engine's use
    // cases, then what the 1.x callers were waiting on. A step that throws is logged and the steps
    // after it still run.
    private suspend fun release() {
        try {
            videoRecorder.stopForShutdown()
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Stopping the recording on release failed", e)
        }
        releaseStep("Ending the session") { session.endLive() }
        releaseStep("Removing the camera observers") { session.stopObserving() }
        releaseStep("Releasing the orientation sensor") { orientation.release() }
        releaseStep("Removing the display listener") { binder.stopListeningForDisplayChanges() }
        // Stop frame delivery before anything it uses goes away, and stop a recording while its
        // VideoCapture is still bound so it finalizes cleanly.
        releaseStep("Stopping frame delivery on release") { framePipeline.stopDelivery() }
        releaseStep("Destroying the engine lifecycle") { session.destroyLifecycle() }
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

        val CLOSED = CameraKResult.Failure(CameraKError.Closed)
        private val NOT_READY = CameraKResult.Failure(CameraKError.NotReady)
    }
}

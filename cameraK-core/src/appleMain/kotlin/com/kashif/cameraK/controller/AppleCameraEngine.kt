@file:OptIn(CameraKEngineApi::class, ExperimentalForeignApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.AspectRatioPreview
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineRecordingEvent
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.HardwareState
import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.engine.PreviewLayerLayout
import com.kashif.cameraK.engine.TraceNames
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.frames.AppleFrameSource
import com.kashif.cameraK.frames.CoreVideoPixelBufferLocker
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.FrameSource
import com.kashif.cameraK.frames.PixelBufferLocker
import com.kashif.cameraK.recording.RecordingEndReason
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.ZoomState
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.AVFoundation.AVCaptureInput
import platform.AVFoundation.AVCaptureVideoOrientation
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.CoreGraphics.CGRect
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIInterfaceOrientation
import kotlin.coroutines.resume

private const val TAG = "CameraK"
private const val MINIMUM_IOS = 16
private const val DEFERRED_START_IOS = 26

/**
 * The iOS engine: an AVCaptureSession behind the engine contract (design §2.4). A plain class; the
 * session, its serial queue and the camera inputs belong to [custom], which this engine owns. It
 * implements [CameraEngine] and delegates to its collaborators, which share one lock:
 *
 * - [generations]: the start generations and the engine state;
 * - [events]: the session's did-start-running, interruption and runtime-error notifications;
 * - [inputs]: the camera a start binds and the lens switches;
 * - [controls]: flash, torch and zoom, read back by key-value observing;
 * - [rotation]: the target and device orientations and the iOS 17 rotation coordinator;
 * - [capabilitySource]: the lenses and what each bound camera reports;
 * - [stills]: captures, written to app-owned storage;
 * - [recorder]: recordings on the movie file output, finalized by stop and close before the camera closes.
 *
 * - The session is made in the constructor and kept across stop and start, so a preview layer made
 *   before the first start already shows it. Inputs and outputs are configured by the first start.
 * - [start] confirms the session runs, or that the system holds it interrupted; otherwise it waits
 *   for AVFoundation's did-start-running, interruption or runtime-error notification.
 * - Below iOS 16 it makes no session, enumerates nothing and every call fails with
 *   `Unsupported(PLATFORM_VERSION)`: a Kotlin/Native library cannot raise its consumer's deployment
 *   target, and every iOS 16 call lives behind this check.
 * - Flash, torch and zoom commands need an Open camera; the target orientation is a stored value any
 *   time before close, which captures and movies read. The device orientation is observed from
 *   UIDeviceOrientationDidChangeNotification between start and stop.
 */
internal class AppleCameraEngine(
    private val config: CameraConfiguration,
    environment: CameraKEnvironment,
    internal val custom: CustomCameraController,
    /** How the device is held now; tests answer for the simulator, which cannot be turned. */
    readDeviceOrientation: () -> UIDeviceOrientation = { UIDevice.currentDevice.orientation },
    /** Begins (true) or ends (false) UIDevice's orientation notifications; tests count the calls. */
    generateOrientationNotifications: (Boolean) -> Unit = ::generateDeviceOrientationNotifications,
    /** Whether the app's Info.plist has a key: a test bundle's cannot change per test. */
    private val hasUsageDescription: (key: String) -> Boolean = ::hasInfoPlistKey,
    /** The rotation coordinators of iOS 17 and later; tests answer for the simulator, which has no camera. */
    rotationSources: RotationSourceFactory = CoordinatorRotationSources,
    /** Locks analysis frames' pixel buffers; tests count the locks. */
    private val pixelBufferLocker: PixelBufferLocker = CoreVideoPixelBufferLocker,
    enumerateLenses: () -> List<LensInfo> = custom::getCameraCapabilities,
) : CameraEngine {
    private val osVersion: Int = environment.runtime.osVersion ?: runningIosMajorVersion()
    private val tracer = environment.runtime.tracer ?: signpostTracer()

    /** The failure every call returns below iOS 16; null from 16 on. */
    private val unsupported: CameraKError? =
        if (osVersion < MINIMUM_IOS) CameraKError.Unsupported(CameraFeature.PLATFORM_VERSION) else null

    private val lock: EngineLock = EngineLock()

    private val generations: EngineGenerations =
        EngineGenerations(lock, tracer, custom, unsupported, frameOutputAttached = { frameSource.outputAttached })
    override val state: StateFlow<EngineState> = generations.state

    private val capabilitySource: EngineCapabilities =
        EngineCapabilities(lock, generations, custom, osVersion, enumerateLenses)
    override val capabilities: StateFlow<CameraCapabilities> get() = capabilitySource.capabilities

    private val rotation: EngineRotation = EngineRotation(
        lock = lock,
        generations = generations,
        custom = custom,
        osVersion = osVersion,
        readDeviceOrientation = readDeviceOrientation,
        generateOrientationNotifications = generateOrientationNotifications,
        rotationSources = rotationSources,
        boundDevice = { controls.observedDevice },
    )
    override val deviceOrientation: StateFlow<DeviceOrientation> = rotation.deviceOrientation
    internal val targetOrientation: DeviceOrientation? get() = rotation.targetOrientation

    private val controls: EngineControls =
        EngineControls(generations, capabilitySource, custom, targetOrientation = { rotation.targetOrientation })
    override val hardware: StateFlow<HardwareState> = controls.hardware
    override val zoom: StateFlow<ZoomState> = controls.zoom
    internal val desiredZoomRatio: Float get() = controls.desiredZoom
    internal val desiredTorchMode: TorchMode get() = controls.desiredTorch

    /** Recordings on the movie file output. */
    internal val recorder = AppleRecorder(
        custom = custom,
        cameraConfig = config,
        osVersion = osVersion,
        hasUsageDescription = hasUsageDescription,
        supportedQualities = capabilitySource::boundQualities,
        videoRotation = rotation::captureRotation,
    )

    override val recordingEvents: Flow<EngineRecordingEvent> = recorder.events

    /** The movie-file-output calls recordings make; tests record them. */
    internal var movieRecorder: MovieRecorder
        get() = recorder.movieRecorder
        set(value) {
            recorder.movieRecorder = value
        }

    /** The microphone's session input for a recording with audio, or why there is none. */
    internal var microphoneInput: () -> Result<AVCaptureInput>
        get() = recorder.microphoneInput
        set(value) {
            recorder.microphoneInput = value
        }

    /** Analysis frames on one 420f video data output (design §8). */
    private val frameSource: AppleFrameSource = AppleFrameSource(
        custom = custom,
        keepOutput = config.frameAnalysis,
        recordingActive = { recorder.isActive },
        rotationDegrees = rotation::frameRotationDegrees,
        onSampleBuffer = generations::onSampleBuffer,
        locker = pixelBufferLocker,
    )
    override val frames: FrameSource = frameSource

    private val scope = CoroutineScope(
        SupervisorJob(environment.parentJob) + environment.dispatchers.default +
            CoroutineExceptionHandler { _, e -> CameraKLogger.e(TAG, "an iOS engine task failed", e) },
    )

    private val stills: EngineStills = EngineStills(lock, generations, custom, config, tracer, environment.dispatchers)

    private val closeDone = Job()
    private val starts = Mutex()

    /** The session, made now so the preview has it before the first start; null below iOS 16. */
    private val session: CaptureSessionOps? = if (unsupported == null) custom.prepareSession() else null

    private val events: EngineSessionEvents = EngineSessionEvents(generations, custom, session, scope)

    private val inputs: EngineInputs = EngineInputs(
        generations = generations,
        custom = custom,
        session = session,
        capabilities = capabilitySource,
        controls = controls,
        rotation = rotation,
        events = events,
        tracer = tracer,
        frameOutputAttached = { frameSource.outputAttached },
    )

    /** Test seam: runs on the session queue between a lens switch's live check and its commit. */
    internal var afterSwitchLiveCheck: () -> Unit
        get() = inputs.afterSwitchLiveCheck
        set(value) {
            inputs.afterSwitchLiveCheck = value
        }

    init {
        custom.captureOrientation = rotation::captureVideoOrientation
        custom.captureRotation = rotation::captureRotation
        custom.recordingInProgress = { recorder.isActive }
        custom.photoFormat = config.imageFormat
        custom.turnsDeferredStartOff = osVersion >= DEFERRED_START_IOS
        recorder.onIdle = frameSource::reconcileOnQueue
        if (unsupported == null) {
            // With frameAnalysis the frame output joins the startup transaction (design §8).
            if (config.frameAnalysis) {
                custom.afterQueuedSessionWork {
                    platformCallback("adding the frame output") { frameSource.reconcileOnQueue() }
                }
            }
            scope.launch { capabilitySource.enumerate() }
        } else {
            capabilitySource.enumerated.complete(Unit)
            closeDone.complete()
        }
    }

    override val preview: PlatformPreviewSource = object :
        PlatformPreviewSource,
        PreviewLayerLayout,
        AspectRatioPreview {
        override val aspectRatio: AspectRatio get() = config.aspectRatio

        override fun createPreviewLayer(): AVCaptureVideoPreviewLayer {
            val avSession = session?.avSession
            val layer = avSession?.let { AVCaptureVideoPreviewLayer(session = it) } ?: AVCaptureVideoPreviewLayer()
            // ResizeAspectFill inside a box of the configured aspect ratio (layOutPreview): the frame
            // is cropped as the still is, so the preview shows what gets captured (#119, w6 decision G1).
            layer.videoGravity = AVLayerVideoGravityResizeAspectFill
            custom.adoptPreviewLayer(layer)
            rotation.adoptPreviewLayer(layer)
            return layer
        }

        override fun layOut(
            layer: AVCaptureVideoPreviewLayer,
            bounds: CValue<CGRect>,
            interfaceOrientation: UIInterfaceOrientation?,
        ) = layOutPreview(layer, bounds, interfaceOrientation)
    }

    /**
     * Main thread: [layer], in a host of [bounds] under an interface in [interfaceOrientation], fills a
     * centred box of the configured aspect ratio (w6 decision G1) and turns: by the rotation
     * coordinator's preview angle when it drives this layer, otherwise to the interface.
     */
    internal fun layOutPreview(
        layer: AVCaptureVideoPreviewLayer,
        bounds: CValue<CGRect>,
        interfaceOrientation: UIInterfaceOrientation?,
    ) {
        layer.setFrame(previewFrame(bounds, config.aspectRatio, interfaceOrientation))
        rotation.turnPreview(layer, interfaceOrientation?.toVideoOrientation())
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────────────────────────

    override suspend fun start(desired: DesiredHardware): CameraKResult<Unit> {
        if (generations.isClosed) return ENGINE_CLOSED
        unsupported?.let { error ->
            generations.failUnusable(error)
            return engineFailure(error)
        }
        if (config.analysisFormat != FrameFormat.YUV_420) {
            // AVCaptureVideoDataOutput delivers CameraK's frames in 420f only (design §6.1, §8).
            val error = CameraKError.Unsupported(CameraFeature.ANALYSIS_FORMAT)
            CameraKLogger.e(TAG, "iOS analysis frames are YUV_420; ${config.analysisFormat} fails the start")
            generations.failUnusable(error)
            return engineFailure(error)
        }
        capabilitySource.enumerated.await()
        return starts.withLock {
            when {
                generations.isClosed -> ENGINE_CLOSED
                generations.isLive -> CameraKResult.Success(Unit)
                else -> {
                    rotation.startObservingOrientation()
                    val started = tracer.beginInterval(TraceNames.START).use { openGeneration(desired) }
                    // Observed between a start and a stop: a start that failed has nothing to stop.
                    if (started is CameraKResult.Failure) rotation.stopObservingOrientation()
                    started
                }
            }
        }
    }

    private suspend fun openGeneration(desired: DesiredHardware): CameraKResult<Unit> =
        suspendCancellableCoroutine { continuation ->
            val generation = generations.open(continuation)
            if (generation == null) {
                continuation.resume(ENGINE_CLOSED)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { cancelStart(generation) }
            custom.afterQueuedSessionWork {
                platformCallback("starting the session") { inputs.startOnQueue(generation, desired) }
            }
        }

    private fun cancelStart(generation: Int) {
        if (generations.cancelStart(generation)) {
            rotation.stopObservingOrientation()
            custom.afterQueuedSessionWork {
                platformCallback("stopping a cancelled start") { custom.stopRunningOnQueue() }
            }
        }
    }

    override suspend fun stop() {
        if (unsupported != null) {
            generations.closeUnusable()
            return
        }
        // A recording is finalized before the camera closes (contract rule 7).
        if (!generations.isClosed) recorder.finishForStop()
        val cut = generations.stop() ?: return
        cut.start?.continuation?.resume(ENGINE_CLOSED)
        rotation.stopObservingOrientation()
        suspendCancellableCoroutine { continuation ->
            custom.afterQueuedSessionWork {
                platformCallback("releasing a prepared recording") { recorder.releasePreparedOnQueue() }
                platformCallback("stopping the session") { custom.stopRunningOnQueue() }
                platformCallback("closing the rotation coordinator") { rotation.unbindOnQueue() }
                continuation.resume(Unit)
            }
        }
    }

    override fun close() {
        val cut = generations.close() ?: return
        cut.start?.continuation?.resume(ENGINE_CLOSED)
        stills.closeAll()
        events.removeObservers()
        rotation.stopObservingOrientation()
        capabilitySource.enumerated.complete(Unit)
        if (session != null) {
            // A recording is finalized before the session is released (contract rule 7).
            recorder.finishForClose {
                custom.cleanupSession()
                custom.afterQueuedSessionWork {
                    controls.stopObservingHardware()
                    rotation.unbindOnQueue()
                    closeDone.complete()
                }
            }
        }
        scope.cancel()
    }

    override suspend fun closeAndJoin() {
        close()
        closeDone.join()
    }

    // ─── Commands ───────────────────────────────────────────────────────────────────────────────

    override suspend fun selectCamera(selection: CameraSelection): CameraKResult<LensInfo> =
        inputs.selectCamera(selection)

    /** The lens switch [selectCamera] makes, passing its result to [done] on the session queue. */
    internal fun switchCamera(selection: CameraSelection, done: (CameraKResult<LensInfo>) -> Unit) =
        inputs.switchCamera(selection, done)

    override suspend fun setFlashMode(mode: FlashMode): CameraKResult<FlashMode> = controls.setFlashMode(mode)

    override suspend fun capture(): CameraKResult<CapturedImage> = stills.capture()

    override suspend fun setZoom(ratio: Float): CameraKResult<ZoomState> = controls.setZoom(ratio)

    override suspend fun setTorchMode(mode: TorchMode): CameraKResult<TorchMode> = controls.setTorchMode(mode)

    /**
     * Stores the target in any state before close; while a camera is bound it is reported in
     * [hardware] before this returns. The next start sets it from its desired hardware.
     */
    override suspend fun setTargetOrientation(orientation: DeviceOrientation?): CameraKResult<Unit> {
        generations.unusable()?.let { return it }
        rotation.targetOrientation = orientation
        return generations.onSessionQueue("setting the target orientation") {
            controls.publishHardware()
            CameraKResult.Success(Unit)
        }
    }

    /** Adds the movie output (and the microphone, for audio) now, so the next start commits nothing. */
    override suspend fun prepareRecording(config: VideoConfiguration): CameraKResult<Unit> {
        generations.notOpen()?.let { return it }
        return recorder.prepare(config)
    }

    /** Returns once AVFoundation reports the recording started; its value is the file's path. */
    override suspend fun startRecording(config: VideoConfiguration): CameraKResult<String> {
        generations.notOpen()?.let { return it }
        return recorder.start(config)
    }

    /** Reaches the movie output in any state before close, so a recording can always be ended. */
    override suspend fun stopRecording(): CameraKResult<RecordedVideo> {
        generations.unusable()?.let { return it }
        return recorder.stop(RecordingEndReason.USER)
    }

    override suspend fun pauseRecording(): CameraKResult<Unit> {
        generations.unusable()?.let { return it }
        return recorder.pause()
    }

    override suspend fun resumeRecording(): CameraKResult<Unit> {
        generations.unusable()?.let { return it }
        return recorder.resume()
    }

    /** The orientation captures and movies take: the target when one is set, otherwise the device's. */
    internal fun captureVideoOrientation(): AVCaptureVideoOrientation = rotation.captureVideoOrientation()

    /**
     * How photos and movies are turned (design §2.4): the target orientation when one is set, else the
     * rotation coordinator's horizon-level capture angle from iOS 17, else the device's orientation.
     */
    internal fun captureRotation(): VideoRotation = rotation.captureRotation()
}

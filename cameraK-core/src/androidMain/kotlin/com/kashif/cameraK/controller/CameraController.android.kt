@file:OptIn(CameraKEngineApi::class, InternalCameraKApi::class)

package com.kashif.cameraK.controller

import android.content.Context
import android.view.Display
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capture.heicCaptureUnsupported
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.PreviewAttachment
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.utils.InternalCameraKApi
import com.kashif.cameraK.utils.getActivityOrNull
import com.kashif.cameraK.video.VideoCaptureResult
import com.kashif.cameraK.video.VideoConfiguration
import java.util.concurrent.ExecutorService

/**
 * The 1.x Android controller: a deprecated facade over an [AndroidCameraEngine] until 2.0's holder
 * commands replace it. Session and binding belong to the engine; nothing here binds or opens a
 * camera on its own. [startSession], [stopSession], [cleanup], the lens and device-type switches,
 * the preview, flash, torch, zoom and the target orientation go through the engine; capture,
 * recording and analyzers keep their 1.x bodies on the engine's collaborators.
 *
 * The facade holds one desired flash, torch and zoom. Its setters change it in any state but closed;
 * while the engine is open they also run the engine's command, and before that they only store the
 * value, which [startSession] passes to the engine and the open applies.
 *
 * A controller built with a public constructor builds and owns its engine. Its session runs while
 * [startSession] has been called and the host's lifecycle is at least STARTED, as the 1.x camera
 * followed the host; the host's destruction cleans it up. One the bridge built has no host lifecycle:
 * the holder starts and stops its engine.
 */
actual class CameraController internal constructor(
    /** The engine this facade runs on. */
    internal val engine: AndroidCameraEngine,
    hostLifecycle: LifecycleOwner?,
) {
    /** The context the controller was built with. */
    val context: Context get() = engine.context

    /** The host lifecycle the controller was built with; the engine, for a controller the bridge built. */
    val lifecycleOwner: LifecycleOwner = hostLifecycle ?: engine

    constructor(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        flashMode: FlashMode,
        torchMode: TorchMode,
        cameraLens: CameraLens,
        imageFormat: ImageFormat,
        qualityPriority: QualityPrioritization,
        directory: Directory,
        cameraDeviceType: CameraDeviceType,
        aspectRatio: AspectRatio,
        targetResolution: Pair<Int, Int>? = null,
        mirrorFrontCamera: Boolean = false,
    ) : this(
        AndroidCameraEngine(
            config = configurationOf(
                flashMode, torchMode, cameraLens, imageFormat, qualityPriority, directory, cameraDeviceType,
                aspectRatio, targetResolution, mirrorFrontCamera,
            ),
            context = context,
            environment = CameraKEnvironment(),
        ),
        lifecycleOwner,
    )

    internal constructor(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        flashMode: FlashMode,
        torchMode: TorchMode,
        cameraLens: CameraLens,
        imageFormat: ImageFormat,
        qualityPriority: QualityPrioritization,
        directory: Directory,
        cameraDeviceType: CameraDeviceType,
        aspectRatio: AspectRatio,
        targetResolution: Pair<Int, Int>?,
        mirrorFrontCamera: Boolean,
        backend: CameraXBackend,
        imageProcessingExecutor: ExecutorService,
        analyzerExecutor: ExecutorService,
        environment: CameraKEnvironment = CameraKEnvironment(),
    ) : this(
        AndroidCameraEngine(
            config = configurationOf(
                flashMode, torchMode, cameraLens, imageFormat, qualityPriority, directory, cameraDeviceType,
                aspectRatio, targetResolution, mirrorFrontCamera,
            ),
            context = context,
            environment = environment,
            backend = backend,
            imageProcessingExecutor = imageProcessingExecutor,
            analyzerExecutor = analyzerExecutor,
        ),
        lifecycleOwner,
    )

    private val config: CameraConfiguration get() = engine.configuration

    /** The facing the next bind or switch asks for. */
    internal var cameraLens: CameraLens = config.cameraLens
        private set

    /** The device type the next bind or switch asks for. */
    internal var cameraDeviceType: CameraDeviceType = config.cameraDeviceType
        private set

    internal val imageFormat: ImageFormat get() = config.imageFormat

    private val hardware: HardwareControls get() = engine.hardwareControls

    // The desired hardware a 1.x caller set, from any thread; the target orientation is the engine's own.
    @Volatile
    private var flashMode: FlashMode = config.flashMode

    @Volatile
    private var torchMode: TorchMode = config.torchMode

    @Volatile
    private var zoomRatio: Float = 1f

    private val frames: FramePipeline get() = engine.framePipeline
    private val stillCapturer: StillCapturer get() = engine.stillCapturer
    private val videoRecorder: VideoRecorder get() = engine.videoRecorder

    // The facade's session runs while startSession is in effect and the host is started.
    private var sessionRequested = false
    private var hostStarted = hostLifecycle == null
    private var orientationListener: ((DeviceOrientation) -> Unit)? = null
    private val host = hostLifecycle

    // The attachment bindCamera made, kept so the attached surface is not collected with the view.
    private var previewAttachment: PreviewAttachment? = null

    private val hostObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> {
                hostStarted = true
                syncSession()
            }
            Lifecycle.Event.ON_STOP -> {
                hostStarted = false
                syncSession()
            }
            Lifecycle.Event.ON_DESTROY -> cleanup()
            else -> Unit
        }
    }

    init {
        engine.onOrientationChanged = { orientation -> orientationListener?.invoke(orientation) }
        host?.let { owner -> engine.onMain { owner.lifecycle.addObserver(hostObserver) } }
    }

    /** The ImageAnalysis use case of the current bind, or null when no analyzer asked for one. */
    var imageAnalyzer: ImageAnalysis?
        get() = frames.analysis
        set(value) {
            frames.analysis = value
        }

    /**
     * Shows the camera in [previewView]. The camera opens from [startSession], not from here; the
     * preview only attaches its surface, and [onCameraReady] runs once the camera is bound.
     */
    fun bindCamera(previewView: PreviewView, onCameraReady: () -> Unit = {}) {
        if (engine.isClosed) {
            CameraKLogger.w(TAG, "Ignoring bind on a released camera controller")
            return
        }
        previewAttachment = attachPreview(previewView)
        engine.onMain { engine.whenBound(onCameraReady) }
    }

    /** Attaches [previewView]'s surface to the engine's preview; closing the result detaches it. */
    @InternalCameraKApi
    @CameraKEngineApi
    fun attachPreview(previewView: PreviewView): PreviewAttachment =
        engine.preview.attach(previewView.surfaceProvider, displayOf(previewView))

    // previewView.display is null until the view is attached (bindCamera runs from a DisposableEffect
    // before layout), so fall back to the hosting activity's display: a landscape start would
    // otherwise build the ViewPort with the wrong rotation until a rebind.
    @Suppress("DEPRECATION")
    private fun displayOf(previewView: PreviewView): Display? = previewView.display
        ?: previewView.context.getActivityOrNull()?.windowManager?.defaultDisplay
        ?: context.getActivityOrNull()?.windowManager?.defaultDisplay

    /**
     * Applies the registered analyzers to the camera. [registerImageAnalyzer] and
     * [unregisterImageAnalyzer] already do this without a rebind, so there is nothing left to call
     * this for; it never rebinds and is harmless before the camera is bound.
     */
    @Deprecated("registerImageAnalyzer and unregisterImageAnalyzer apply analyzers themselves; remove the call.")
    fun updateImageAnalyzer() {
        onMainThread {
            if (frames.analysisRequested && frames.analysis == null) bindImageAnalysis() else frames.applyConsumers()
        }
    }

    /**
     * Registers an [ImageAnalysis.Analyzer] to receive camera frames.
     * Multiple analyzers can be registered simultaneously — they share a single
     * [ImageAnalysis] use case via ref-counted frame dispatch. Safe to call from any thread; only the
     * first registration binds anything.
     *
     * Analyzers run one after another on the analysis thread, and the frame is released once every
     * analyzer has closed it, so close it before returning or copy what you need first. Each
     * analyzer reads the frame's planes through its own read-only buffers; a throwing analyzer is
     * logged and its share of the frame closed.
     */
    fun registerImageAnalyzer(analyzer: ImageAnalysis.Analyzer) {
        frames.add(analyzer)
        onMainThread {
            if (frames.analysis == null) bindImageAnalysis() else frames.applyConsumers()
        }
    }

    /**
     * Unregisters a previously registered analyzer; the camera is not rebound. Frames dispatched
     * after this returns do not reach it, but a frame already being dispatched may.
     */
    fun unregisterImageAnalyzer(analyzer: ImageAnalysis.Analyzer) {
        frames.remove(analyzer)
        onMainThread { frames.applyConsumers() }
    }

    /**
     * Adds the ImageAnalysis to a running camera, once. Before the first bind there is nothing to do:
     * that bind includes it. During a recording, rebinding the group would end the recording, so the
     * analysis is bound alone until the next bind puts it in the group.
     */
    private fun bindImageAnalysis() {
        if (!engine.isBound) return
        if (!videoRecorder.isRecording) {
            engine.launch { engine.rebind() }
            return
        }
        val analysis = frames.build(engine.pinnedPhysicalId)
        try {
            // The bound camera rather than a bare lens facing: a pinned physical lens must carry the
            // same selector-level pin as the main bind.
            engine.binder.bindAlone({ engine.boundCameraOr(cameraLens, cameraDeviceType) }, analysis)
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Binding image analysis during a recording failed", e)
        }
    }

    /** Runs [block] on the main thread, where CameraX bindings change, unless the controller was released. */
    private fun onMainThread(block: () -> Unit) {
        ContextCompat.getMainExecutor(context).execute {
            if (!engine.isClosed) block()
        }
    }

    actual suspend fun takePictureToFile(): ImageCaptureResult {
        if (!engine.isClosed && imageFormat == ImageFormat.HEIC) return heicCaptureUnsupported(TAG)
        return stillCapturer.takePictureToFile()
    }

    actual fun toggleFlashMode() {
        setFlashMode(
            when (flashMode) {
                FlashMode.OFF -> FlashMode.ON
                FlashMode.ON -> FlashMode.AUTO
                FlashMode.AUTO -> FlashMode.OFF
            },
        )
    }

    actual fun setFlashMode(mode: FlashMode) {
        if (engine.isClosed) return
        flashMode = mode
        applyHardware("FlashMode.$mode", { engine.storeDesired(flashMode = mode) }) { engine.setFlashMode(mode) }
    }

    /** The flash mode the bound capture reports, or null before a bind. */
    actual fun getFlashMode(): FlashMode? = hardware.boundFlashMode()

    // Android has no AUTO torch, so the cycle is OFF and ON.
    actual fun toggleTorchMode() = setTorchMode(if (torchMode == TorchMode.ON) TorchMode.OFF else TorchMode.ON)

    actual fun setTorchMode(mode: TorchMode) {
        if (engine.isClosed) return
        if (mode == TorchMode.AUTO) {
            CameraKLogger.w(TAG, "TorchMode.AUTO is not supported on Android; the torch stays $torchMode")
            return
        }
        torchMode = mode
        applyHardware("TorchMode.$mode", { engine.storeDesired(torchMode = mode) }) { engine.setTorchMode(mode) }
    }

    actual fun setZoom(zoomRatio: Float) {
        if (engine.isClosed) return
        this.zoomRatio = zoomRatio
        applyHardware("Zoom $zoomRatio", { engine.storeDesired(zoomRatio = zoomRatio) }) { engine.setZoom(zoomRatio) }
    }

    actual fun getZoom(): Float = hardware.getZoom()

    actual fun getMaxZoom(): Float = hardware.getMaxZoom()

    /** The torch mode last set, or configured; the camera reports its own through the holder's uiState. */
    actual fun getTorchMode(): TorchMode? = torchMode

    // Runs [command] while the engine is open, else stores the value for the next open. A 1.x setter
    // returns nothing, so a refused command is logged here.
    private fun applyHardware(what: String, store: () -> Unit, command: suspend () -> CameraKResult<*>) {
        engine.launch {
            if (!engine.isOpen) {
                store()
                return@launch
            }
            val result = command()
            if (result is CameraKResult.Failure) CameraKLogger.w(TAG, "$what was not applied: ${reason(result.error)}")
        }
    }

    private fun reason(error: CameraKError): String = when {
        error is CameraKError.Unsupported && hardware.hasFlashUnit() == false -> "this camera has no flash unit"
        error is CameraKError.Unsupported -> "${error.feature} is not supported on this camera"
        else -> error::class.simpleName ?: "the engine refused it"
    }

    actual fun toggleCameraLens() {
        // A lens switch unbinds and rebinds, which would tear down a live recording session.
        if (videoRecorder.isRecording) {
            CameraKLogger.w(TAG, "Ignoring lens switch while recording")
            return
        }
        cameraLens = if (cameraLens == CameraLens.BACK) CameraLens.FRONT else CameraLens.BACK
        switchCamera()
    }

    actual fun getCameraLens(): CameraLens? = cameraLens

    actual fun getImageFormat(): ImageFormat = imageFormat

    actual fun getAspectRatio(): AspectRatio = config.aspectRatio

    actual fun getQualityPrioritization(): QualityPrioritization = config.qualityPrioritization

    actual fun getPreferredCameraDeviceType(): CameraDeviceType = cameraDeviceType

    actual fun setPreferredCameraDeviceType(deviceType: CameraDeviceType) {
        if (cameraDeviceType == deviceType) return
        // Same as the lens switch: this rebinds and would kill a live recording.
        if (videoRecorder.isRecording) {
            CameraKLogger.w(TAG, "Ignoring device type change while recording")
            return
        }
        cameraDeviceType = deviceType
        switchCamera()
    }

    // The engine switches a live session; a closed one opens on the new camera at its next start.
    private fun switchCamera() {
        val selection = CameraSelection.ByLens(cameraLens, cameraDeviceType)
        engine.launch { engine.selectCamera(selection) }
    }

    actual fun getCameraCapabilities(): CameraCapabilities = engine.capabilities.value
        .takeIf { it.allLenses.isNotEmpty() }
        ?: CameraCapabilities(engine.selectorResolver.lensSnapshot().map { it.info })

    /** Opens the camera through the engine; it stays open until [stopSession] or [cleanup]. */
    actual fun startSession() {
        sessionRequested = true
        syncSession()
    }

    /**
     * Closes the camera and unbinds the use cases this controller bound, and no other CameraX
     * consumer's. The next [startSession] binds again.
     */
    actual fun stopSession() {
        sessionRequested = false
        engine.launch {
            engine.stop()
            engine.unbindOwnUseCases()
        }
    }

    private fun syncSession() {
        if (engine.isClosed) return
        val run = sessionRequested && hostStarted
        engine.launch { if (run) engine.start(desired()) else engine.stop() }
    }

    private fun desired() = DesiredHardware(
        selection = CameraSelection.ByLens(cameraLens, cameraDeviceType),
        flashMode = flashMode,
        torchMode = torchMode,
        zoomRatio = zoomRatio,
        targetOrientation = engine.orientation.targetOrientation,
    )

    /**
     * Adds a listener for captured images. Safe to call from any thread. On Android the listener runs
     * on the controller's image-processing thread, after the capture has been saved and read back,
     * never on the main thread.
     */
    actual fun addImageCaptureListener(listener: (ByteArray) -> Unit) {
        stillCapturer.addListener(listener)
    }

    /** Removes a listener added with [addImageCaptureListener]. Safe to call from any thread. */
    actual fun removeImageCaptureListener(listener: (ByteArray) -> Unit) {
        stillCapturer.removeListener(listener)
    }

    actual suspend fun startRecording(configuration: VideoConfiguration): String =
        videoRecorder.startRecording(configuration)

    actual suspend fun stopRecording(): VideoCaptureResult = videoRecorder.stopRecording()

    actual suspend fun pauseRecording() {
        videoRecorder.pauseRecording()
    }

    actual suspend fun resumeRecording() {
        videoRecorder.resumeRecording()
    }

    actual fun getDeviceOrientation(): DeviceOrientation = engine.orientation.deviceOrientation

    /** Reports orientation changes while the session runs; the sensor runs only then. */
    actual fun setOnOrientationChangedListener(callback: ((DeviceOrientation) -> Unit)?) {
        orientationListener = callback
    }

    /**
     * Sets the engine's one target orientation, in any state, as 1.x did: applied to the bound
     * outputs, reported by the engine while open, and passed by [startSession] to the next start.
     * Null follows the display rotation again.
     */
    actual fun setTargetOrientation(orientation: DeviceOrientation?) {
        engine.onMain { engine.applyTargetOrientation(orientation) }
    }

    /**
     * Releases the camera: closes the engine. Terminal and idempotent: afterwards no bind reaches
     * CameraX, a capture still in flight returns [ImageCaptureResult.Error], and late CameraX
     * callbacks are ignored.
     */
    actual fun cleanup() {
        if (engine.isClosed) return
        host?.let { owner -> engine.onMain { owner.lifecycle.removeObserver(hostObserver) } }
        previewAttachment = null
        engine.close()
    }

    private companion object {
        const val TAG = "CameraK"

        fun configurationOf(
            flashMode: FlashMode,
            torchMode: TorchMode,
            cameraLens: CameraLens,
            imageFormat: ImageFormat,
            qualityPriority: QualityPrioritization,
            directory: Directory,
            cameraDeviceType: CameraDeviceType,
            aspectRatio: AspectRatio,
            targetResolution: Pair<Int, Int>?,
            mirrorFrontCamera: Boolean,
        ) = CameraConfiguration(
            flashMode = flashMode,
            torchMode = torchMode,
            cameraLens = cameraLens,
            imageFormat = imageFormat,
            qualityPrioritization = qualityPriority,
            cameraDeviceType = cameraDeviceType,
            aspectRatio = aspectRatio,
            targetResolution = targetResolution,
            directory = directory,
            mirrorFrontCamera = mirrorFrontCamera,
        )
    }
}

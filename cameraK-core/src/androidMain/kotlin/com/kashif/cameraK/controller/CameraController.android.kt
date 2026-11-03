package com.kashif.cameraK.controller

import android.content.Context
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.UseCase
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensEnumerator
import com.kashif.cameraK.capture.heicCaptureUnsupported
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoCaptureResult
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Android-specific implementation of [CameraController] using CameraX: a facade over its
 * collaborators, [UseCaseBinder], [CameraSelectorResolver], [HardwareControls], [StillCapturer],
 * [VideoRecorder], [FramePipeline] and [OrientationTracker].
 */
actual class CameraController internal constructor(
    val context: Context,
    val lifecycleOwner: LifecycleOwner,
    flashMode: FlashMode,
    torchMode: TorchMode,
    internal var cameraLens: CameraLens,
    internal val imageFormat: ImageFormat,
    internal val qualityPriority: QualityPrioritization,
    internal val directory: Directory,
    internal var cameraDeviceType: CameraDeviceType,
    internal val aspectRatio: AspectRatio,
    internal val targetResolution: Pair<Int, Int>?,
    internal val mirrorFrontCamera: Boolean,
    private val backend: CameraXBackend,
    private val imageProcessingExecutor: ExecutorService,
    private val analyzerExecutor: ExecutorService,
) {

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
        context = context,
        lifecycleOwner = lifecycleOwner,
        flashMode = flashMode,
        torchMode = torchMode,
        cameraLens = cameraLens,
        imageFormat = imageFormat,
        qualityPriority = qualityPriority,
        directory = directory,
        cameraDeviceType = cameraDeviceType,
        aspectRatio = aspectRatio,
        targetResolution = targetResolution,
        mirrorFrontCamera = mirrorFrontCamera,
        backend = CameraXBackend.Default,
        imageProcessingExecutor = Executors.newFixedThreadPool(2, cameraKThreads("CameraK-image-processing")),
        analyzerExecutor = Executors.newSingleThreadExecutor(cameraKThreads("CameraK-analyzer")),
    )

    // CameraX's file output always encodes JPEG, so that is what every capture file holds, whatever
    // imageFormat asks for; its name and MIME type follow the bytes.
    private val outputFormat = ImageFormat.JPEG

    /** The engine this facade runs on. Red stub: A1' builds it. */
    internal val engine: AndroidCameraEngine get() = TODO("A1'")

    init {
        if (imageFormat != outputFormat && imageFormat != ImageFormat.HEIC) {
            CameraKLogger.w(TAG, "ImageFormat.$imageFormat is not supported for Android captures; saving JPEG instead")
        }
    }

    // Set once by cleanup(). Every main-executor callback (provider ready, capture saved, recording
    // finalized) checks it first, so a callback that lands after release cannot rebind the camera,
    // touch the shut-down executors or resume a capture twice.
    private val released = atomic(false)

    // Set by resolveCamera when the requested device type is a physical sub-lens of a
    // logical multi-camera, and baked into every use-case builder of the bind (see
    // pinnedPhysicalId on ResolvedCamera).
    private var pinnedPhysicalId: String? = null

    // Set when a portrait↔landscape flip is detected during recording (when rebinding would tear
    // down the recording). The deferred rebind runs once the recording finalizes.
    @Volatile
    private var pendingViewPortRebind = false

    private val selectorResolver = CameraSelectorResolver { LensEnumerator.snapshot(context) }
    private val orientation = OrientationTracker(context)
    private val binder = UseCaseBinder(
        context = context,
        lifecycleOwner = lifecycleOwner,
        aspectRatio = aspectRatio,
        targetResolution = targetResolution,
        isReleased = { released.value },
        onDisplayChanged = ::onDisplayChanged,
    )
    private val files = OutputFileFactory(context, directory, outputFormat)
    private val stillCapturer = StillCapturer(
        context = context,
        backend = backend,
        files = files,
        imageProcessingExecutor = imageProcessingExecutor,
        qualityPriority = qualityPriority,
        isReleased = { released.value },
        metadata = ::captureMetadata,
    )
    private val hardware = HardwareControls(
        context = context,
        flashMode = flashMode,
        torchMode = torchMode,
        camera = { binder.camera },
        imageCapture = { stillCapturer.imageCapture },
    )
    private val frames = FramePipeline(backend, analyzerExecutor)
    private val videoHost = object : VideoSessionHost {
        override val isReleased: Boolean get() = released.value

        override val previewView: PreviewView? get() = binder.previewView

        override suspend fun rebindAndAwait(previewView: PreviewView) =
            this@CameraController.rebindAndAwait(previewView)

        override fun rebindAfterRecordingEndedByCameraX() {
            pendingViewPortRebind = false
            binder.previewView?.let { bindCamera(it) }
        }

        // Applies any rotation flip that was deferred while recording was in progress.
        override fun onNoActiveRecording() {
            if (pendingViewPortRebind && !released.value) {
                pendingViewPortRebind = false
                rebindIfViewPortOrientationChanged()
            }
        }
    }
    private val videoRecorder = VideoRecorder(context, backend, files, videoHost)

    /** The ImageAnalysis use case of the current bind, or null when no analyzer asked for one. */
    var imageAnalyzer: ImageAnalysis?
        get() = frames.analysis
        set(value) {
            frames.analysis = value
        }

    fun bindCamera(previewView: PreviewView, onCameraReady: () -> Unit = {}) {
        if (released.value) {
            CameraKLogger.w(TAG, "Ignoring bind on a released camera controller")
            return
        }
        binder.attach(previewView)

        backend.requestProvider(context, ContextCompat.getMainExecutor(context)) { providerResult ->
            if (released.value) {
                CameraKLogger.d(TAG, "Dropping a bind that resolved after the controller was released")
                return@requestProvider
            }
            try {
                binder.useProvider(providerResult.getOrThrow())

                val resolutionSelector = binder.resolutionSelector()
                // Must run before any use-case builder: it decides pinnedPhysicalId.
                val camera = resolveCamera()

                val preview = binder.buildPreview(resolutionSelector, pinnedPhysicalId, previewView)
                stillCapturer.configure(resolutionSelector, hardware.flashMode, pinnedPhysicalId)
                // Only build VideoCapture when a recording is active/about to start.
                videoRecorder.configureForBind()

                // Rebuild rather than reuse the existing instance: its baked-in Camera2Interop pin
                // reflects the PREVIOUS lens/device type. Reusing it after a switch would silently
                // analyze frames from the wrong physical camera (or lose the pin). Constructed here
                // (after resolveCamera set pinnedPhysicalId, before the use-case group is
                // assembled below) so it binds via this bind, not a second nested one.
                frames.analysis = if (frames.analysisRequested) frames.build(pinnedPhysicalId) else null

                // Align capture rotation with the display rotation that also drives the ViewPort and
                // the preview box, so all three agree (WYSIWYG). Relying on the accelerometer
                // OrientationEventListener for this let the crop (display-based) and the EXIF
                // (sensor-based) disagree — producing a portrait-rotated capture of a landscape crop
                // (and vice-versa) when the device was flat or the display was orientation-locked (#136).
                applyCaptureRotation(previewView)

                val useCases = buildList<UseCase> {
                    add(preview)
                    add(checkNotNull(stillCapturer.imageCapture))
                    videoRecorder.useCaseForBind()?.let { add(it) }
                    frames.analysis?.let { add(it) }
                }
                binder.bindGroup(camera, useCases, previewView)?.let(hardware::applyDesiredHardwareState)

                onCameraReady()
            } catch (exc: Exception) {
                CameraKLogger.e(TAG, "Binding the use cases for $cameraDeviceType failed", exc)
            }
        }
    }

    /** The camera for the current facing and device type; also decides [pinnedPhysicalId]. */
    private fun resolveCamera(): ResolvedCamera {
        val resolved = selectorResolver.resolve(cameraLens, cameraDeviceType)
        pinnedPhysicalId = resolved.pinnedPhysicalId
        return resolved
    }

    private fun onDisplayChanged(previewView: PreviewView) {
        // Auto mode only; an explicit target orientation stays as set.
        if (orientation.targetOrientation == null) applyCaptureRotation(previewView)
        rebindIfViewPortOrientationChanged()
    }

    /**
     * Rebinds the camera when the display rotation flips between portrait and landscape, so the
     * (immutable) ViewPort is rebuilt with the matching Rational.
     */
    private fun rebindIfViewPortOrientationChanged() {
        val pv = binder.previewView ?: return
        if (!binder.viewPortOrientationChanged(pv)) return

        if (videoRecorder.isRecording) {
            // Don't rebind mid-recording: unbinding to rebind would tear down the VideoCapture session.
            // Defer it — the recording's Finalize callback performs the rebind once it's safe.
            pendingViewPortRebind = true
            return
        }
        bindCamera(pv)
    }

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
        val pv = binder.previewView
        if (binder.camera == null || pv == null) return
        if (!videoRecorder.isRecording) {
            bindCamera(pv)
            return
        }
        val analysis = frames.build(pinnedPhysicalId)
        try {
            // resolveCamera() rather than a bare lens facing: a pinned physical lens must carry the
            // same selector-level pin as the main bind.
            binder.bindAlone(::resolveCamera, analysis)
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Binding image analysis during a recording failed", e)
        }
    }

    /** Runs [block] on the main thread, where CameraX bindings change, unless the controller was released. */
    private fun onMainThread(block: () -> Unit) {
        ContextCompat.getMainExecutor(context).execute {
            if (!released.value) block()
        }
    }

    actual suspend fun takePictureToFile(): ImageCaptureResult {
        if (!released.value && imageFormat == ImageFormat.HEIC) return heicCaptureUnsupported(TAG)
        return stillCapturer.takePictureToFile()
    }

    /**
     * Capture metadata: mirror the saved image horizontally for the front camera when configured,
     * so the photo matches the mirrored preview (#112).
     */
    private fun captureMetadata(): ImageCapture.Metadata = ImageCapture.Metadata().apply {
        isReversedHorizontal = mirrorFrontCamera && cameraLens == CameraLens.FRONT
    }

    actual fun toggleFlashMode() = hardware.toggleFlashMode()

    actual fun setFlashMode(mode: FlashMode) = hardware.setFlashMode(mode)

    actual fun getFlashMode(): FlashMode? = hardware.boundFlashMode()

    actual fun toggleTorchMode() = hardware.toggleTorchMode()

    actual fun setTorchMode(mode: TorchMode) = hardware.setTorchMode(mode)

    actual fun setZoom(zoomRatio: Float) = hardware.setZoom(zoomRatio)

    actual fun getZoom(): Float = hardware.getZoom()

    actual fun getMaxZoom(): Float = hardware.getMaxZoom()

    actual fun getTorchMode(): TorchMode? = hardware.torchMode

    actual fun toggleCameraLens() {
        // A lens switch unbinds and rebinds, which would tear down a live recording session.
        if (videoRecorder.isRecording) {
            CameraKLogger.w(TAG, "Ignoring lens switch while recording")
            return
        }
        cameraLens = if (cameraLens == CameraLens.BACK) CameraLens.FRONT else CameraLens.BACK
        binder.previewView?.let { bindCamera(it) }
    }

    actual fun getCameraLens(): CameraLens? = cameraLens

    actual fun getImageFormat(): ImageFormat = imageFormat

    actual fun getAspectRatio(): AspectRatio = aspectRatio

    actual fun getQualityPrioritization(): QualityPrioritization = qualityPriority

    actual fun getPreferredCameraDeviceType(): CameraDeviceType = cameraDeviceType

    actual fun setPreferredCameraDeviceType(deviceType: CameraDeviceType) {
        if (cameraDeviceType == deviceType) return
        // Same as the lens switch: this rebinds and would kill a live recording.
        if (videoRecorder.isRecording) {
            CameraKLogger.w(TAG, "Ignoring device type change while recording")
            return
        }
        cameraDeviceType = deviceType
        // Live switch: re-bind the use cases with a camera selector for the new device type
        // (this rebind keeps the session/plugins; the field alone has no effect until a bind).
        binder.previewView?.let { bindCamera(it) }
    }

    actual fun getCameraCapabilities(): CameraCapabilities =
        CameraCapabilities(selectorResolver.lensSnapshot().map { it.info })

    /** Nothing to start: the session runs from [bindCamera] until [stopSession]. */
    actual fun startSession() = Unit

    /**
     * Unbinds the use cases this controller bound, and no other CameraX consumer's. Call on the main
     * thread, like every other CameraX binding call: the set of bound use cases is main-thread state.
     */
    actual fun stopSession() {
        binder.unbindOwnUseCases()
    }

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

    /** Rebinds and waits for the bind to finish, for at most [STOP_FINALIZE_TIMEOUT_MS]. */
    private suspend fun rebindAndAwait(previewView: PreviewView) {
        withTimeoutOrNull(STOP_FINALIZE_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { c -> bindCamera(previewView) { if (c.isActive) c.resume(Unit) } }
        }
    }

    actual suspend fun stopRecording(): VideoCaptureResult = videoRecorder.stopRecording()

    actual suspend fun pauseRecording() {
        videoRecorder.pauseRecording()
    }

    actual suspend fun resumeRecording() {
        videoRecorder.resumeRecording()
    }

    actual fun getDeviceOrientation(): DeviceOrientation = orientation.deviceOrientation

    actual fun setOnOrientationChangedListener(callback: ((DeviceOrientation) -> Unit)?) {
        orientation.setOnOrientationChangedListener(callback)
    }

    actual fun setTargetOrientation(orientation: DeviceOrientation?) {
        this.orientation.targetOrientation = orientation
        // applyCaptureRotation honors the override when set, and otherwise uses the DISPLAY rotation
        // (consistent with auto mode) — so clearing the override (null) immediately reverts to the
        // display rotation instead of a possibly-stale sensor orientation. Fall back to the sensor
        // orientation only if there's no preview view yet.
        binder.previewView?.let { applyCaptureRotation(it) }
            ?: applyRotation((orientation ?: this.orientation.deviceOrientation).toSurfaceRotation())
    }

    /**
     * Sets the capture rotation from the current display rotation (auto mode) or the explicit
     * target orientation. Keeping capture rotation tied to the display — the same source as the
     * ViewPort and the preview box — guarantees the saved photo's orientation matches what the user
     * saw (#136).
     */
    private fun applyCaptureRotation(previewView: PreviewView) {
        val override = orientation.targetOrientation
        applyRotation(override?.toSurfaceRotation() ?: binder.displayRotation(previewView))
    }

    private fun applyRotation(rotation: Int) {
        stillCapturer.imageCapture?.targetRotation = rotation
        videoRecorder.videoCapture?.targetRotation = rotation
    }

    /**
     * Releases the camera. Terminal and idempotent: afterwards no bind reaches CameraX, a capture
     * still in flight returns [ImageCaptureResult.Error], and late CameraX callbacks are ignored.
     * Call on the main thread, like every other CameraX binding call.
     */
    actual fun cleanup() {
        if (!released.compareAndSet(false, true)) return

        orientation.release()
        binder.stopListeningForDisplayChanges()

        // Stop frame delivery before anything it uses goes away, and stop a recording while its
        // VideoCapture is still bound so it finalizes cleanly.
        frames.stopDelivery()
        videoRecorder.stopForRelease()

        // Release this controller's own use cases (not unbindAll: the process-wide provider may hold
        // another owner's use cases).
        try {
            binder.unbindOwnUseCases()
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Unbinding use cases on release failed", e)
        }

        frames.clearConsumers()
        stillCapturer.clearListeners()
        videoRecorder.failUnclaimedOutcome()

        // A capture still in flight would otherwise wait for a callback that is now ignored.
        stillCapturer.failInFlight()

        imageProcessingExecutor.shutdown()
        analyzerExecutor.shutdown()
    }

    private companion object {
        const val TAG = "CameraK"

        fun cameraKThreads(name: String): ThreadFactory {
            val count = AtomicInteger()
            return ThreadFactory { runnable ->
                Thread(runnable, "$name-${count.incrementAndGet()}").apply { isDaemon = true }
            }
        }
    }
}

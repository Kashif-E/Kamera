package com.kashif.cameraK.controller

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Rational
import android.util.Size
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalZeroShutterLag
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.kashif.cameraK.capabilities.AndroidLensDescriptor
import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capabilities.LensEnumerator
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
import com.kashif.cameraK.utils.getActivityOrNull
import com.kashif.cameraK.video.VideoCaptureResult
import com.kashif.cameraK.video.VideoConfiguration
import com.kashif.cameraK.video.VideoQuality
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android-specific implementation of [CameraController] using CameraX.
 */
actual class CameraController internal constructor(
    val context: Context,
    val lifecycleOwner: LifecycleOwner,
    internal var flashMode: FlashMode,
    internal var torchMode: TorchMode,
    internal var cameraLens: CameraLens,
    internal var imageFormat: ImageFormat,
    internal var qualityPriority: QualityPrioritization,
    internal var directory: Directory,
    internal var cameraDeviceType: CameraDeviceType,
    internal var aspectRatio: AspectRatio,
    internal var targetResolution: Pair<Int, Int>?,
    internal var mirrorFrontCamera: Boolean,
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

    init {
        if (imageFormat != outputFormat) {
            CameraKLogger.w(TAG, "ImageFormat.$imageFormat is not supported for Android captures; saving JPEG instead")
        }
    }

    private var cameraProvider: CameraBinding? = null

    // Every use case this controller has bound and not yet unbound. ProcessCameraProvider is shared
    // by the whole process, so rebinds and teardown unbind exactly these, never unbindAll(). Main
    // thread only, like every binding call.
    private val boundUseCases = linkedSetOf<UseCase>()
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null
    private var camera: Camera? = null
    var imageAnalyzer: ImageAnalysis? = null
    private var previewView: PreviewView? = null

    // Frame consumers (plugins' analyzers). The bound ImageAnalysis feeds them through one
    // MultiplexingAnalyzer that reads this list on every frame, so registering or removing one never
    // touches CameraX bindings.
    private val analyzerConsumers = atomic(emptyList<ImageAnalysis.Analyzer>())
    private val frameMultiplexer = MultiplexingAnalyzer { analyzerConsumers.value }

    // Set by the first registration and kept for the controller's lifetime: from then on every bind
    // includes one ImageAnalysis, so later consumers join without a rebind.
    @Volatile
    private var analysisRequested = false

    // Whether frameMultiplexer is set on the current imageAnalyzer. Main thread only.
    private var multiplexerAttached = false

    // Video recording
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: RecordingSession? = null
    private var recordingOutputFile: File? = null

    // The outcome of the current recording, completed by its Finalize however the recording ended,
    // and kept until a stopRecording() claims it. A recording CameraX ends by itself (source
    // inactive, disk full, a limit reached) is still reported by the next stop.
    private var recordingOutcome: CompletableDeferred<VideoCaptureResult>? = null

    // VideoCapture is only bound while recording. A 16:9 VideoCapture in the photo UseCaseGroup
    // shares the ViewPort and shrinks the common field of view, so every still gets cropped/zoomed
    // (#136) — unless StreamSharing happens to merge it. Binding it lazily keeps photos full-FOV.
    @Volatile
    private var includeVideoUseCase = false

    @Volatile
    private var pendingVideoQuality = VideoQuality.FHD

    // Added and removed on any thread (plugins), iterated on imageProcessingExecutor: copy-on-write,
    // so every iteration is a consistent snapshot and concurrent registrations are never lost.
    private val imageCaptureListeners = CopyOnWriteArrayList<(ByteArray) -> Unit>()

    // Set once by cleanup(). Every main-executor callback (provider ready, capture saved, recording
    // finalized) checks it first, so a callback that lands after release cannot rebind the camera,
    // touch the shut-down executors or resume a capture twice.
    private val released = atomic(false)

    // Captures whose CameraX callback has not arrived yet. Whoever removes a continuation (the
    // callback or cleanup()) is the one that resumes it.
    private val inFlightCaptures: MutableSet<CancellableContinuation<ImageCaptureResult>> =
        Collections.newSetFromMap(ConcurrentHashMap())
    private val maxConcurrentCaptures = 3

    // One permit per capture in flight. Each capture gives its permit back exactly once, through
    // its CaptureSlot, whichever of success, error or cancellation settles it first.
    private val captureSlots = Semaphore(maxConcurrentCaptures)
    private val stopFinalizeTimeoutMs = 5000L

    // Hardware doesn't change at runtime; enumerate once.
    private var cachedLensSnapshot: List<AndroidLensDescriptor>? = null

    // Set by createCameraSelector when the requested device type is a physical sub-lens of a
    // logical multi-camera. CameraSelector.setPhysicalCameraId ALONE silently no-ops on real
    // hardware — it must be paired with Camera2Interop.Extender.setPhysicalCameraId on EVERY
    // use-case builder (verified on real hardware). Consulted by bindCamera,
    // configureCaptureUseCase and buildImageAnalysis.
    private var pinnedPhysicalId: String? = null

    internal fun lensSnapshot(): List<AndroidLensDescriptor> =
        cachedLensSnapshot ?: LensEnumerator.snapshot(context).also {
            // Don't cache an empty result: a transient camera-service failure on the first call
            // would otherwise permanently disable lens switching for the controller's lifetime.
            if (it.isNotEmpty()) cachedLensSnapshot = it
        }

    // Portrait/landscape category of the display rotation used to build the current ViewPort. The
    // ViewPort is immutable once bound, so when the display flips category we must rebind to rebuild
    // it — otherwise the capture crop stays at the old orientation (reintroduces #136 after rotation).
    private var lastViewPortPortrait: Boolean? = null

    // Set when a portrait↔landscape flip is detected during recording (when rebinding would tear
    // down the recording). The deferred rebind runs once the recording finalizes.
    @Volatile
    private var pendingViewPortRebind = false

    @OptIn(ExperimentalCamera2Interop::class)
    fun bindCamera(previewView: PreviewView, onCameraReady: () -> Unit = {}) {
        if (released.value) {
            CameraKLogger.w(TAG, "Ignoring bind on a released camera controller")
            return
        }
        this.previewView = previewView
        listenForDisplayChanges()

        backend.requestProvider(context, ContextCompat.getMainExecutor(context)) { providerResult ->
            if (released.value) {
                CameraKLogger.d(TAG, "Dropping a bind that resolved after the controller was released")
                return@requestProvider
            }
            try {
                cameraProvider = providerResult.getOrThrow()
                unbindOwnUseCases()

                val resolutionSelector = createResolutionSelector()
                // Must run before any use-case builder: it decides pinnedPhysicalId.
                val cameraSelector = createCameraSelector()

                preview = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .apply { pinnedPhysicalId?.let { Camera2Interop.Extender(this).setPhysicalCameraId(it) } }
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                configureCaptureUseCase(resolutionSelector)
                // Only build VideoCapture when a recording is active/about to start — see field doc.
                if (includeVideoUseCase) configureVideoCaptureUseCase(pendingVideoQuality) else videoCapture = null

                // Rebuild rather than reuse the existing instance: its baked-in Camera2Interop pin
                // reflects the PREVIOUS lens/device type. Reusing it after a switch would silently
                // analyze frames from the wrong physical camera (or lose the pin). Constructed here
                // (after createCameraSelector set pinnedPhysicalId, before the use-case group is
                // assembled below) so it binds via this bindToLifecycle call, not a second nested one.
                imageAnalyzer = if (analysisRequested) buildImageAnalysis() else null

                // Align capture rotation with the display rotation that also drives the ViewPort and
                // the preview box, so all three agree (WYSIWYG). Relying on the accelerometer
                // OrientationEventListener for this let the crop (display-based) and the EXIF
                // (sensor-based) disagree — producing a portrait-rotated capture of a landscape crop
                // (and vice-versa) when the device was flat or the display was orientation-locked (#136).
                applyCaptureRotation(previewView)

                val useCaseGroupBuilder = UseCaseGroup.Builder()
                    .addUseCase(preview!!)
                    .addUseCase(imageCapture!!)

                if (includeVideoUseCase) videoCapture?.let { useCaseGroupBuilder.addUseCase(it) }
                imageAnalyzer?.let { useCaseGroupBuilder.addUseCase(it) }

                // Build the ViewPort explicitly from the configured aspect ratio rather than reading
                // previewView.viewPort. The latter is null until the view is laid out (so the crop is
                // often dropped at bind time) and, with a FIT_CENTER preview, reflects the full sensor
                // buffer — which left the captured still at the sensor ratio (e.g. 16:9) even when a
                // 4:3 preview was requested (#136). An explicit ViewPort crops preview + capture +
                // analyzer to the same ratio, so what you see equals what you capture.
                buildViewPort(previewView)?.let { useCaseGroupBuilder.setViewPort(it) }

                val useCaseGroup = useCaseGroupBuilder.build()

                camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    useCaseGroup,
                )
                boundUseCases += useCaseGroup.useCases
                camera?.let(::applyDesiredHardwareState)

                onCameraReady()
            } catch (exc: Exception) {
                CameraKLogger.e("CameraK", "==> Use case binding failed for $cameraDeviceType: ${exc.message}")
                CameraKLogger.e("CameraK", "Unhandled exception", exc)
            }
        }
    }

    /** The resolution selector for the configured aspect ratio and target resolution. */
    private fun createResolutionSelector(): ResolutionSelector {
        // Always honor the configured aspect ratio. When a target resolution is also set, keep the
        // ratio as the primary constraint and use the resolution as the preferred size within it —
        // previously targetResolution dropped the ratio entirely, so a 16:9 target produced 16:9
        // captures even when 4:3 was requested (#136).
        val builder = ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspectRatio.toCameraXAspectRatioStrategy())
        targetResolution?.let { (w, h) ->
            builder.setResolutionStrategy(
                ResolutionStrategy(Size(w, h), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            )
        }
        return builder.build()
    }

    private fun AspectRatio.toCameraXAspectRatioStrategy(): AspectRatioStrategy = when (this) {
        AspectRatio.RATIO_16_9, AspectRatio.RATIO_9_16 -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        AspectRatio.RATIO_4_3 -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        AspectRatio.RATIO_1_1 -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY // closest available
    }

    /**
     * Builds a [ViewPort] matching the configured aspect ratio so every use case (preview, capture,
     * analyzer) is cropped to the same field of view. The [Rational] is expressed as width:height in
     * the display's CURRENT orientation (the convention `PreviewView.getViewPort()` uses), so it is
     * flipped for portrait — e.g. `RATIO_4_3` is `3:4` in portrait and `4:3` in landscape.
     */
    private fun buildViewPort(previewView: PreviewView): ViewPort? {
        val rotation = currentDisplayRotation(previewView)
        // The ViewPort Rational is width:height in the display's CURRENT orientation. In portrait a
        // "4:3" capture is taller than wide (3:4); only in landscape is it 4:3. Hard-coding the
        // landscape Rational forced a landscape crop in portrait — preview and photo came out 4:3
        // when 3:4 was expected (#136). Flip width/height for portrait.
        val portrait = rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180
        lastViewPortPortrait = portrait
        val rational = when (aspectRatio) {
            AspectRatio.RATIO_16_9, AspectRatio.RATIO_9_16 ->
                if (portrait) Rational(9, 16) else Rational(16, 9)
            AspectRatio.RATIO_4_3 ->
                if (portrait) Rational(3, 4) else Rational(4, 3)
            AspectRatio.RATIO_1_1 -> Rational(1, 1)
        }
        return ViewPort.Builder(rational, rotation)
            .setScaleType(ViewPort.FILL_CENTER)
            .build()
    }

    /**
     * Rebinds the camera when the display rotation flips between portrait and landscape, so the
     * (immutable) ViewPort is rebuilt with the matching Rational. Gated on the *display* rotation —
     * an activity locked to one orientation never rotates the display, so it never needlessly
     * rebinds, while an unlocked one keeps capture cropped to what's actually on screen.
     */
    private fun rebindIfViewPortOrientationChanged() {
        val pv = previewView ?: return
        val rotation = currentDisplayRotation(pv)
        val portrait = rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180
        if (lastViewPortPortrait == null || portrait == lastViewPortPortrait) return

        if (activeRecording != null) {
            // Don't rebind mid-recording: unbinding to rebind would tear down the VideoCapture session.
            // Defer it — the recording's Finalize callback performs the rebind once it's safe.
            pendingViewPortRebind = true
            return
        }
        bindCamera(pv)
    }

    /**
     * Current display rotation. `previewView.display` is null until the view is attached (bindCamera
     * runs from a DisposableEffect before layout), so fall back to the hosting activity's display —
     * otherwise a landscape start would build the ViewPort with the wrong rotation and crop to the
     * wrong orientation until a rebind.
     */
    private fun currentDisplayRotation(previewView: PreviewView): Int =
        currentDisplay(previewView)?.rotation ?: Surface.ROTATION_0

    @Suppress("DEPRECATION")
    private fun currentDisplay(previewView: PreviewView): Display? = previewView.display
        ?: previewView.context.getActivityOrNull()?.windowManager?.defaultDisplay
        ?: context.getActivityOrNull()?.windowManager?.defaultDisplay

    // Display events, not the accelerometer, drive the ViewPort and the capture rotation: the
    // accelerometer crosses a bucket hundreds of ms before the display rotates, and a host that
    // handles orientation changes itself is never recreated, so nothing else would rebuild the crop
    // (#136). Trade-off: in an orientation-locked host, captures follow the display orientation.
    private var displayListener: DisplayManager.DisplayListener? = null

    /** Registers [displayListener] once, on the main thread. */
    private fun listenForDisplayChanges() {
        if (displayListener != null) return
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (released.value) return
                val pv = previewView ?: return
                if (currentDisplay(pv)?.displayId != displayId) return
                // Auto mode only; an explicit target orientation stays as set.
                if (targetOrientation == null) applyCaptureRotation(pv)
                rebindIfViewPortOrientationChanged()
            }

            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        displayListener = listener
    }

    private fun stopListeningForDisplayChanges() {
        val listener = displayListener ?: return
        displayListener = null
        (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.unregisterDisplayListener(listener)
    }

    /**
     * Creates a camera selector for the current facing + requested device type, resolved
     * against the real lens snapshot (35mm-equivalent classification) instead of raw
     * focal-length thresholds.
     *
     * - WIDE_ANGLE/DEFAULT: no filter (the default logical camera opens wide).
     * - Requested type maps to a top-level camera id: exact-id filter.
     * - Requested type maps to a physical sub-lens: physical-camera pin (selector half;
     *   the use-case builders apply the Camera2Interop half via [pinnedPhysicalId]).
     * - Type unavailable on this facing: warn and fall back to the default camera.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun createCameraSelector(): CameraSelector {
        pinnedPhysicalId = null
        val builder = CameraSelector.Builder()
            .requireLensFacing(cameraLens.toCameraXLensFacing())

        if (cameraDeviceType == CameraDeviceType.DEFAULT || cameraDeviceType == CameraDeviceType.WIDE_ANGLE) {
            return builder.build()
        }

        val target = lensSnapshot().firstOrNull {
            it.info.lens == cameraLens && !it.info.isExternal && it.info.deviceType == cameraDeviceType
        }
        if (target == null) {
            CameraKLogger.w("CameraK", "$cameraDeviceType not available for $cameraLens, using default camera")
            return builder.build()
        }

        return if (target.isPhysicalChild) {
            pinnedPhysicalId = target.info.id
            // setPhysicalCameraId ALONE does not influence which logical camera CameraSelector
            // picks: CameraSelector.select() (verified against CameraX 1.5.1 source) resolves
            // purely from the CameraFilter set, and the physical id is only consulted later by
            // the use-case builders' Camera2Interop.Extender. Without this filter, on a facing
            // with multiple logical multi-cameras, selection could land on the wrong logical
            // camera while still (silently) trying to pin a physical id that belongs to a
            // different one. Constrain selection to the physical child's parent logical camera;
            // fall back to the unfiltered set if for some reason it's not offered.
            builder.setPhysicalCameraId(target.info.id)
                .addCameraFilter { cameraInfos ->
                    cameraInfos.filter { Camera2CameraInfo.from(it).cameraId == target.parentLogicalId }
                        .ifEmpty { cameraInfos }
                }.build()
        } else {
            builder.addCameraFilter { cameraInfos ->
                cameraInfos.filter { Camera2CameraInfo.from(it).cameraId == target.info.id }
                    .ifEmpty {
                        CameraKLogger.w("CameraK", "Camera ${target.info.id} not offered by CameraX, using default")
                        cameraInfos
                    }
            }.build()
        }
    }

    /**
     * Configures the image capture use case. [QualityPrioritization.NONE] states no preference and
     * captures at maximum quality, as it always did on a device that was not short of memory.
     */
    @OptIn(ExperimentalZeroShutterLag::class, ExperimentalCamera2Interop::class)
    private fun configureCaptureUseCase(resolutionSelector: ResolutionSelector) {
        imageCapture = ImageCapture.Builder()
            .setFlashMode(flashMode.toCameraXFlashMode())
            .setCaptureMode(
                when (qualityPriority) {
                    QualityPrioritization.QUALITY -> ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                    QualityPrioritization.SPEED -> ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
                    QualityPrioritization.BALANCED -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                    QualityPrioritization.NONE -> ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                },
            )
            .setResolutionSelector(resolutionSelector)
            .apply { pinnedPhysicalId?.let { Camera2Interop.Extender(this).setPhysicalCameraId(it) } }
            .build()
    }

    /**
     * Applies the registered analyzers to the camera. [registerImageAnalyzer] and
     * [unregisterImageAnalyzer] already do this without a rebind, so there is nothing left to call
     * this for; it never rebinds and is harmless before the camera is bound.
     */
    @Deprecated("registerImageAnalyzer and unregisterImageAnalyzer apply analyzers themselves; remove the call.")
    fun updateImageAnalyzer() {
        onMainThread {
            if (analysisRequested && imageAnalyzer == null) bindImageAnalysis() else applyAnalyzerConsumers()
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
        analyzerConsumers.update { it + analyzer }
        analysisRequested = true
        onMainThread {
            if (imageAnalyzer == null) bindImageAnalysis() else applyAnalyzerConsumers()
        }
    }

    /**
     * Unregisters a previously registered analyzer; the camera is not rebound. Frames dispatched
     * after this returns do not reach it, but a frame already being dispatched may.
     */
    fun unregisterImageAnalyzer(analyzer: ImageAnalysis.Analyzer) {
        analyzerConsumers.update { it - analyzer }
        onMainThread { applyAnalyzerConsumers() }
    }

    /**
     * Builds the [ImageAnalysis] use case, baking in [pinnedPhysicalId] via Camera2Interop, and
     * attaches [frameMultiplexer] when someone is consuming. Construction only — does not bind.
     * Callers that already own a bindToLifecycle call (bindCamera) apply the result to the same
     * use-case group instead of triggering a redundant nested bind.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun buildImageAnalysis(): ImageAnalysis {
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .apply { pinnedPhysicalId?.let { Camera2Interop.Extender(this).setPhysicalCameraId(it) } }
            .build()
        imageAnalyzer = analysis
        multiplexerAttached = false
        applyAnalyzerConsumers()
        return analysis
    }

    /** Attaches [frameMultiplexer] while anyone consumes frames and detaches it when no one does. */
    private fun applyAnalyzerConsumers() {
        val analysis = imageAnalyzer ?: return
        val wanted = analyzerConsumers.value.isNotEmpty()
        if (wanted == multiplexerAttached) return
        // Run analysis off the main thread — frame work (e.g. JPEG-encoding the analyzer frame,
        // QR/OCR) is too heavy to block the UI thread.
        if (wanted) {
            backend.setAnalyzer(analysis, analyzerExecutor, frameMultiplexer)
        } else {
            backend.clearAnalyzer(analysis)
        }
        multiplexerAttached = wanted
    }

    /**
     * Adds the ImageAnalysis to a running camera, once. Before the first bind there is nothing to do:
     * that bind includes it. During a recording, rebinding the group would end the recording, so the
     * analysis is bound alone until the next bind puts it in the group.
     */
    private fun bindImageAnalysis() {
        val pv = previewView
        if (camera == null || pv == null) return
        if (activeRecording == null) {
            bindCamera(pv)
            return
        }
        val analysis = buildImageAnalysis()
        try {
            // createCameraSelector() rather than a bare lens-facing selector: a pinned physical lens
            // must carry the same selector-level pin as the main bind.
            cameraProvider?.bindToLifecycle(lifecycleOwner, createCameraSelector(), analysis)
            boundUseCases += analysis
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

    actual suspend fun takePictureToFile(): ImageCaptureResult = suspendCancellableCoroutine { cont ->
        if (released.value) {
            cont.resume(ImageCaptureResult.Error(controllerReleased()))
            return@suspendCancellableCoroutine
        }
        if (!captureSlots.tryAcquire()) {
            CameraKLogger.w("CameraK", "Burst queue full, dropping frame")
            cont.resume(ImageCaptureResult.Error(Exception("Burst queue full, capture rejected")))
            return@suspendCancellableCoroutine
        }
        val slot = CaptureSlot()
        // Registered before the capture starts, so a cancellation at any point releases the slot.
        cont.invokeOnCancellation {
            inFlightCaptures.remove(cont)
            slot.release()
        }
        // A caller that had already left was cancelled on entry, and the handler above released its slot.
        if (!cont.isActive) return@suspendCancellableCoroutine

        performCaptureToFile(cont, slot)
    }

    /** A capture's hold on one of [captureSlots]; the first [release] gives it back, later ones do nothing. */
    private inner class CaptureSlot {
        private val settled = atomic(false)

        fun release() {
            if (settled.compareAndSet(false, true)) captureSlots.release()
        }
    }

    /**
     * Capture metadata: mirror the saved image horizontally for the front camera when configured,
     * so the photo matches the mirrored preview (#112).
     */
    private fun captureMetadata(): ImageCapture.Metadata = ImageCapture.Metadata().apply {
        isReversedHorizontal = mirrorFrontCamera && cameraLens == CameraLens.FRONT
    }

    /**
     * Perform fast file-based capture without ByteArray processing.
     * Directly saves to final destination and returns file path.
     */
    private fun performCaptureToFile(continuation: CancellableContinuation<ImageCaptureResult>, slot: CaptureSlot) {
        val capture = imageCapture ?: run {
            slot.release()
            continuation.resume(ImageCaptureResult.Error(Exception("ImageCapture use case is not initialized.")))
            return
        }
        // Gallery destinations are written by CameraX through MediaStore; everything else is a file
        // in app-specific storage.
        // A destination that cannot be created fails this capture instead of escaping takePictureToFile.
        val (outputFile, galleryValues) = try {
            if (directory.isGallery) null to galleryImageValues() else createFinalOutputFile() to null
        } catch (e: java.io.IOException) {
            CameraKLogger.e(TAG, "Creating the capture file failed", e)
            slot.release()
            continuation.resume(ImageCaptureResult.Error(e))
            return
        }
        val options = if (galleryValues != null) {
            ImageCapture.OutputFileOptions.Builder(
                context.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                galleryValues,
            )
        } else {
            ImageCapture.OutputFileOptions.Builder(checkNotNull(outputFile))
        }.setMetadata(captureMetadata()).build()

        inFlightCaptures.add(continuation)
        backend.takePicture(
            capture,
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    slot.release()
                    val galleryUri = if (outputFile == null) output.savedUri else null
                    if (released.value) {
                        // cleanup() already told the caller this capture failed; don't leave an
                        // unreported file or gallery entry behind or post to the shut-down executor.
                        CameraKLogger.d(TAG, "Discarding a capture saved after the controller was released")
                        outputFile?.delete()
                        galleryUri?.let(::deleteGalleryEntry)
                        return
                    }

                    val result = when {
                        outputFile != null -> ImageCaptureResult.SuccessWithFile(outputFile.absolutePath)
                        galleryUri != null -> ImageCaptureResult.SuccessWithUri(galleryUri.toString())
                        else -> {
                            CameraKLogger.e(TAG, "CameraX saved a gallery capture without reporting its entry")
                            ImageCaptureResult.Error(IllegalStateException("The saved capture has no gallery entry"))
                        }
                    }

                    // Notify capture listeners (e.g. ImageSaverPlugin auto-save) off the main
                    // thread, reading the just-saved image only when someone is listening.
                    if (result !is ImageCaptureResult.Error && imageCaptureListeners.isNotEmpty()) {
                        imageProcessingExecutor.execute { deliverToCaptureListeners(outputFile, galleryUri) }
                    }

                    if (inFlightCaptures.remove(continuation)) {
                        continuation.resume(result)
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    CameraKLogger.e("CameraK", "Image capture failed: ${exc.message}", exc)
                    slot.release()
                    outputFile?.delete() // Clean up failed capture file
                    val reportError = {
                        if (inFlightCaptures.remove(continuation)) {
                            continuation.resume(ImageCaptureResult.Error(exc))
                        }
                    }
                    if (galleryValues == null) {
                        reportError()
                        return
                    }
                    runOffMain {
                        deleteFailedGalleryCapture(galleryValues)
                        reportError()
                    }
                }
            },
        )
    }

    /**
     * The MediaStore row CameraX inserts for a gallery capture. From API 29 the folder is a
     * RELATIVE_PATH and needs no permission; below it the row names its file in the public folder,
     * which needs WRITE_EXTERNAL_STORAGE (declared with maxSdkVersion 28 in the library manifest).
     */
    private fun galleryImageValues(): ContentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.MIME_TYPE, outputFormat.mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.DISPLAY_NAME, newCaptureFileName("IMG", outputFormat.extension))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${directory.publicDirectoryName}/$CAMERAK_FOLDER")
        } else {
            @Suppress("DEPRECATION") // Below API 29 this is the public folder MediaStore writes to.
            val parent = File(
                Environment.getExternalStoragePublicDirectory(directory.publicDirectoryName),
                CAMERAK_FOLDER,
            ).also { it.mkdirs() }
            val file = newCaptureFile(parent, "IMG", outputFormat.extension)
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.DATA, file.absolutePath)
        }
    }

    /**
     * When writing a gallery capture fails, CameraX has already inserted its MediaStore row and does
     * not delete it (CameraX 1.5.3 FileUtil.copyFileToMediaStore marks it not pending and throws), and
     * onError does not say which row it was. The row is found by what this capture gave it: its
     * file path below API 29, otherwise its display name, which is unique by construction.
     */
    private fun deleteFailedGalleryCapture(values: ContentValues) {
        val legacyPath = values.getAsString(MediaStore.MediaColumns.DATA)
        val (column, value) = if (legacyPath != null) {
            MediaStore.MediaColumns.DATA to legacyPath
        } else {
            MediaStore.MediaColumns.DISPLAY_NAME to values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
        }
        try {
            context.contentResolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "$column = ?", arrayOf(value))
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Deleting the gallery entry of a failed capture failed", e)
        }
        // Below API 29 the gallery file was claimed before CameraX inserted its row.
        legacyPath?.let { File(it).delete() }
    }

    /** Runs MediaStore and file work off the main thread, or here once the controller has shut down. */
    private fun runOffMain(block: () -> Unit) {
        try {
            imageProcessingExecutor.execute(block)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            CameraKLogger.w(TAG, "Image processing has shut down; cleaning up the capture on the calling thread", e)
            block()
        }
    }

    private fun deleteGalleryEntry(uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Deleting a discarded gallery capture failed", e)
        }
    }

    /**
     * Runs on [imageProcessingExecutor]. Nothing may escape: an exception here reaches the thread's
     * uncaught handler and kills the host process. Each listener is isolated from the others.
     */
    private fun deliverToCaptureListeners(file: File?, galleryUri: Uri?) {
        val bytes = try {
            file?.readBytes()
                ?: galleryUri?.let { uri ->
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw java.io.IOException("The gallery capture could not be opened")
                }
                ?: return
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Reading the captured image for capture listeners failed", e)
            return
        } catch (e: OutOfMemoryError) {
            CameraKLogger.e(TAG, "Reading the captured image for capture listeners ran out of memory", e)
            return
        }
        for (listener in imageCaptureListeners) {
            try {
                listener(bytes)
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "Image capture listener failed", e)
            }
        }
    }

    actual fun toggleFlashMode() {
        flashMode = when (flashMode) {
            FlashMode.OFF -> FlashMode.ON
            FlashMode.ON -> FlashMode.AUTO
            FlashMode.AUTO -> FlashMode.OFF
        }
        imageCapture?.flashMode = flashMode.toCameraXFlashMode()
    }

    actual fun setFlashMode(mode: FlashMode) {
        flashMode = mode
        imageCapture?.flashMode = mode.toCameraXFlashMode()
    }

    actual fun getFlashMode(): FlashMode? {
        fun Int.toCameraKFlashMode(): FlashMode? = when (this) {
            ImageCapture.FLASH_MODE_ON -> FlashMode.ON
            ImageCapture.FLASH_MODE_OFF -> FlashMode.OFF
            ImageCapture.FLASH_MODE_AUTO -> FlashMode.AUTO
            else -> null
        }

        return imageCapture?.flashMode?.toCameraKFlashMode()
    }

    // The zoom ratio the user last asked for; null until they do. Re-applied after every bind,
    // because each bind gives CameraX a camera at 1x.
    @Volatile
    private var desiredZoom: Float? = null

    /**
     * Applies the torch and zoom the configuration or the user asked for to a freshly bound camera:
     * CameraX opens every bound camera with the torch off and no zoom.
     */
    private fun applyDesiredHardwareState(camera: Camera) {
        if (torchMode != TorchMode.OFF) applyTorch(camera)
        desiredZoom?.let { applyZoom(camera, it) }
    }

    /** Asks [camera] for [torchMode], if it has a flash unit; CameraX has no AUTO torch, so AUTO is on. */
    private fun applyTorch(camera: Camera) {
        val mode = torchMode
        val on = mode != TorchMode.OFF
        if (!camera.cameraInfo.hasFlashUnit()) {
            if (on) CameraKLogger.w(TAG, "TorchMode.$mode requested, but this camera has no flash unit")
            return
        }
        logIfFailed(if (on) "Turning the torch on" else "Turning the torch off") {
            camera.cameraControl.enableTorch(on)
        }
    }

    /** Asks [camera] for [zoom], clamped to the range it reports, which can start below 1x. */
    private fun applyZoom(camera: Camera, zoom: Float) {
        val state = camera.cameraInfo.zoomState.value
        val ratio = state?.let { zoom.coerceIn(it.minZoomRatio, it.maxZoomRatio) } ?: zoom
        logIfFailed("Setting the zoom") { camera.cameraControl.setZoomRatio(ratio) }
    }

    /** Logs the failure of a CameraX control call instead of dropping its future. */
    private fun logIfFailed(operation: String, call: () -> ListenableFuture<*>) {
        val future = try {
            call()
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "$operation failed", e)
            return
        }
        future.addListener({
            try {
                future.get()
            } catch (e: java.util.concurrent.ExecutionException) {
                val cause = e.cause ?: e
                // CameraX cancels a pending request when a newer one replaces it, as in a pinch.
                if (cause is CameraControl.OperationCanceledException) {
                    CameraKLogger.d(TAG, "$operation was superseded")
                } else {
                    CameraKLogger.w(TAG, "$operation failed", cause)
                }
            } catch (e: java.util.concurrent.CancellationException) {
                CameraKLogger.d(TAG, "$operation was cancelled")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    actual fun toggleTorchMode() {
        setTorchMode(
            when (torchMode) {
                TorchMode.OFF -> TorchMode.ON
                TorchMode.ON -> TorchMode.AUTO
                TorchMode.AUTO -> TorchMode.OFF
            },
        )
    }

    actual fun setTorchMode(mode: TorchMode) {
        torchMode = mode
        if (mode == TorchMode.AUTO) {
            CameraKLogger.w(TAG, "TorchMode.AUTO not natively supported, using ON")
        }
        camera?.let { applyTorch(it) }
    }

    actual fun setZoom(zoomRatio: Float) {
        desiredZoom = zoomRatio
        camera?.let { applyZoom(it, zoomRatio) }
    }

    actual fun getZoom(): Float = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f

    actual fun getMaxZoom(): Float = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f

    actual fun getTorchMode(): TorchMode? = torchMode

    actual fun toggleCameraLens() {
        // A lens switch unbinds and rebinds, which would tear down a live recording session.
        if (activeRecording != null) {
            CameraKLogger.w("CameraController", "Ignoring lens switch while recording")
            return
        }
        cameraLens = if (cameraLens == CameraLens.BACK) CameraLens.FRONT else CameraLens.BACK
        previewView?.let { bindCamera(it) }
    }

    actual fun getCameraLens(): CameraLens? = cameraLens

    actual fun getImageFormat(): ImageFormat = imageFormat

    actual fun getAspectRatio(): AspectRatio = aspectRatio

    actual fun getQualityPrioritization(): QualityPrioritization = qualityPriority

    actual fun getPreferredCameraDeviceType(): CameraDeviceType = cameraDeviceType

    actual fun setPreferredCameraDeviceType(deviceType: CameraDeviceType) {
        if (cameraDeviceType == deviceType) return
        // Same as the lens switch: this rebinds and would kill a live recording.
        if (activeRecording != null) {
            CameraKLogger.w("CameraController", "Ignoring device type change while recording")
            return
        }
        cameraDeviceType = deviceType
        // Live switch: re-bind the use cases with a camera selector for the new device type
        // (this rebind keeps the session/plugins; the field alone has no effect until a bind).
        previewView?.let { bindCamera(it) }
    }

    actual fun getCameraCapabilities(): CameraCapabilities = CameraCapabilities(lensSnapshot().map { it.info })

    /** Nothing to start: the session runs from [bindCamera] until [stopSession]. */
    actual fun startSession() = Unit

    /**
     * Unbinds the use cases this controller bound, and no other CameraX consumer's. Call on the main
     * thread, like every other CameraX binding call: the set of bound use cases is main-thread state.
     */
    actual fun stopSession() {
        unbindOwnUseCases()
    }

    /** Unbinds every use case this controller bound, and nothing another CameraX consumer bound. */
    private fun unbindOwnUseCases() {
        if (boundUseCases.isEmpty()) return
        val own = boundUseCases.toTypedArray()
        boundUseCases.clear()
        cameraProvider?.unbind(*own)
    }

    private fun unbindOwn(useCase: UseCase) {
        if (boundUseCases.remove(useCase)) cameraProvider?.unbind(useCase)
    }

    /**
     * Adds a listener for captured images. Safe to call from any thread. On Android the listener runs
     * on the controller's image-processing thread, after the capture has been saved and read back,
     * never on the main thread.
     */
    actual fun addImageCaptureListener(listener: (ByteArray) -> Unit) {
        imageCaptureListeners.add(listener)
    }

    /** Removes a listener added with [addImageCaptureListener]. Safe to call from any thread. */
    actual fun removeImageCaptureListener(listener: (ByteArray) -> Unit) {
        imageCaptureListeners.remove(listener)
    }

    /** The file a capture that does not go to the gallery is written to. */
    private fun createTempFile(): File {
        val cameraKDir = appCaptureDirectory(context)
        if (!cameraKDir.exists()) {
            cameraKDir.mkdirs()
        }
        return newCaptureFile(cameraKDir, "IMG", outputFormat.extension)
    }

    /**
     * Creates final output file for direct capture (used by takePictureToFile).
     * Same as createTempFile but semantically represents the final destination.
     */
    private fun createFinalOutputFile(): File = createTempFile()

    private fun FlashMode.toCameraXFlashMode(): Int = when (this) {
        FlashMode.ON -> ImageCapture.FLASH_MODE_ON
        FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
        FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
    }

    private fun CameraLens.toCameraXLensFacing(): Int = when (this) {
        CameraLens.FRONT -> CameraSelector.LENS_FACING_FRONT
        CameraLens.BACK -> CameraSelector.LENS_FACING_BACK
    }

    // ═══════════════════════════════════════════════════════════════
    // Video Recording
    // ═══════════════════════════════════════════════════════════════

    private fun configureVideoCaptureUseCase(quality: VideoQuality = VideoQuality.FHD) {
        try {
            val cameraXQuality = when (quality) {
                VideoQuality.SD -> Quality.SD
                VideoQuality.HD -> Quality.HD
                VideoQuality.FHD -> Quality.FHD
                VideoQuality.UHD -> Quality.UHD
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        cameraXQuality,
                        FallbackStrategy.higherQualityOrLowerThan(cameraXQuality),
                    ),
                )
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
        } catch (e: Exception) {
            CameraKLogger.w("CameraK", "VideoCapture use case not supported: ${e.message}")
            videoCapture = null
        }
    }

    actual suspend fun startRecording(configuration: VideoConfiguration): String {
        // Reject a second start: starting again would overwrite activeRecording and orphan the
        // first recording's session (it could never be stopped/finalized).
        if (activeRecording != null) throw IllegalStateException("A recording is already in progress")
        // VideoCapture is bound lazily (it isn't part of the photo session — see includeVideoUseCase).
        // Bind it now and wait for the rebind before recording.
        val videoBind = if (videoCapture == null) {
            val pv = previewView ?: throw IllegalStateException("Camera preview not ready for recording")
            pendingVideoQuality = configuration.quality
            includeVideoUseCase = true
            pv
        } else {
            null
        }
        return try {
            // Don't hang forever if the bind fails (bindCamera only invokes onCameraReady on
            // success). On timeout, startRecordingInternal reports a clean error.
            videoBind?.let { rebindAndAwait(it) }
            startRecordingInternal(configuration)
        } catch (e: Exception) {
            // Recording never started, whether it failed or the caller was cancelled during the bind:
            // drop the lazily-bound VideoCapture and rebind, otherwise the flag stays set and every
            // later photo silently reverts to the cropped FOV (#136).
            if (includeVideoUseCase) {
                includeVideoUseCase = false
                withContext(NonCancellable) { previewView?.let { rebindAndAwait(it) } }
            }
            throw e
        }
    }

    /** Rebinds and waits for the bind to finish, for at most [stopFinalizeTimeoutMs]. */
    private suspend fun rebindAndAwait(previewView: PreviewView) {
        withTimeoutOrNull(stopFinalizeTimeoutMs) {
            suspendCancellableCoroutine<Unit> { c -> bindCamera(previewView) { if (c.isActive) c.resume(Unit) } }
        }
    }

    private suspend fun startRecordingInternal(configuration: VideoConfiguration): String =
        suspendCancellableCoroutine { cont ->
            val vc = videoCapture ?: run {
                cont.resumeWithException(IllegalStateException("VideoCapture use case not available"))
                return@suspendCancellableCoroutine
            }

            val outputFile = createVideoOutputFile(configuration)
            val outcome = CompletableDeferred<VideoCaptureResult>()
            val outputOptions = FileOutputOptions.Builder(outputFile).build()

            val hasAudioPermission = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

            // Finalize always arrives on the main executor, after the session below is assigned.
            var session: RecordingSession? = null
            session = try {
                backend.startRecording(
                    videoCapture = vc,
                    context = context,
                    output = outputOptions,
                    withAudio = configuration.enableAudio && hasAudioPermission,
                    executor = ContextCompat.getMainExecutor(context),
                ) { event ->
                    outcome.complete(finalizedResult(event, outputFile))
                    if (recordingOutputFile == outputFile) recordingOutputFile = null
                    // stopRecording() clears activeRecording before it waits, so a recording that
                    // is still active here was ended by CameraX, with no stop call to restore the
                    // session. A late Finalize of an earlier recording must not touch a newer one.
                    if (activeRecording === session) {
                        activeRecording = null
                        if (includeVideoUseCase && !released.value) {
                            // Drop VideoCapture so stills are full-FOV again (#136).
                            includeVideoUseCase = false
                            pendingViewPortRebind = false
                            previewView?.let { bindCamera(it) }
                        }
                    }
                    // Apply any rotation flip that was deferred while recording was in progress,
                    // unless a newer recording is running.
                    if (activeRecording == null && pendingViewPortRebind && !released.value) {
                        pendingViewPortRebind = false
                        rebindIfViewPortOrientationChanged()
                    }
                }
            } catch (e: Exception) {
                // CameraX refused to start: nothing records, so nothing is left for a stop to await.
                cont.resumeWithException(e)
                return@suspendCancellableCoroutine
            }

            if (recordingOutcome != null) {
                CameraKLogger.w(TAG, "The last recording's outcome was never claimed by stopRecording(); discarding it")
            }
            recordingOutcome = outcome
            recordingOutputFile = outputFile
            activeRecording = session
            cont.resume(outputFile.absolutePath)
        }

    actual suspend fun stopRecording(): VideoCaptureResult {
        val outcome = recordingOutcome
            ?: return VideoCaptureResult.Error(IllegalStateException("No active recording"))
        recordingOutcome = null
        activeRecording?.stop()
        activeRecording = null
        var claimed = false
        try {
            // Don't wait forever: if the CameraX finalize event never arrives this would hang the
            // caller and leave isRecording stuck true.
            val result = withTimeoutOrNull(stopFinalizeTimeoutMs) { outcome.await() }
                ?: VideoCaptureResult.Error(IllegalStateException("Timed out waiting for recording to finalize"))
            claimed = true
            return result
        } finally {
            // A cancelled caller did not claim the outcome: keep it for the next stop, unless a new
            // recording has already started.
            if (!claimed && recordingOutcome == null && activeRecording == null) recordingOutcome = outcome
            // Drop VideoCapture and rebind back to the full-FOV photo session, also when the caller
            // was cancelled. Await the rebind before returning: a fire-and-forget rebind lets an
            // immediate photo (or a quick startRecording) race the pending bind — capturing on the
            // old UseCaseGroup, or the late rebind unbinding a freshly-started recording's use cases.
            if (includeVideoUseCase) {
                includeVideoUseCase = false
                withContext(NonCancellable) { previewView?.let { rebindAndAwait(it) } }
            }
        }
    }

    /**
     * What a Finalize means for the caller. Several errors end the recording but leave a playable
     * file (the camera was taken away, a size or duration limit or a full disk stopped it); those
     * report the file, and the error is logged.
     */
    private fun finalizedResult(event: RecordingFinalized, file: File): VideoCaptureResult {
        val playable = file.isFile && file.length() > 0
        val keepsFile = !event.hasError || (event.error in PLAYABLE_FINALIZE_ERRORS && playable)
        if (!keepsFile) {
            CameraKLogger.e(TAG, "Recording failed with CameraX error ${event.error}", event.cause)
            return VideoCaptureResult.Error(Exception("Recording error code: ${event.error}", event.cause))
        }
        if (event.hasError) {
            CameraKLogger.w(TAG, "Recording ended by CameraX error ${event.error}; its file is playable", event.cause)
        }
        // Notify MediaStore so video appears in Gallery
        notifyMediaStoreVideo(file)
        return VideoCaptureResult.Success(
            filePath = file.absolutePath,
            durationMs = event.recordedDurationNanos / 1_000_000,
        )
    }

    actual suspend fun pauseRecording() {
        activeRecording?.pause()
    }

    actual suspend fun resumeRecording() {
        activeRecording?.resume()
    }

    private fun createVideoOutputFile(config: VideoConfiguration): File {
        val dir = if (config.outputDirectory != null) {
            File(config.outputDirectory).also { it.mkdirs() }
        } else {
            when (directory) {
                Directory.PICTURES, Directory.DCIM -> File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "CameraK",
                )
                Directory.DOCUMENTS -> appCaptureDirectory(context)
            }.also { it.mkdirs() }
        }
        return newCaptureFile(dir, config.filePrefix, "mp4")
    }

    private fun notifyMediaStoreVideo(file: File) {
        try {
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("video/mp4"),
                null,
            )
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Notifying MediaStore of a recorded video failed", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Device Orientation
    // ═══════════════════════════════════════════════════════════════

    @Volatile
    private var currentDeviceOrientation = DeviceOrientation.PORTRAIT

    @Volatile
    private var orientationChangedCallback: ((DeviceOrientation) -> Unit)? = null
    private var orientationEventListener: OrientationEventListener? = null

    @Volatile
    private var targetOrientation: DeviceOrientation? = null

    actual fun getDeviceOrientation(): DeviceOrientation = currentDeviceOrientation

    actual fun setOnOrientationChangedListener(callback: ((DeviceOrientation) -> Unit)?) {
        orientationChangedCallback = callback
        if (callback != null) {
            if (orientationEventListener == null) {
                orientationEventListener = object : OrientationEventListener(context) {
                    override fun onOrientationChanged(angle: Int) {
                        if (angle == ORIENTATION_UNKNOWN) return
                        val newOrientation = when {
                            angle in 315..359 || angle in 0..44 -> DeviceOrientation.PORTRAIT
                            angle in 45..134 -> DeviceOrientation.LANDSCAPE_RIGHT
                            angle in 135..224 -> DeviceOrientation.PORTRAIT_UPSIDE_DOWN
                            angle in 225..314 -> DeviceOrientation.LANDSCAPE_LEFT
                            else -> return
                        }
                        if (newOrientation != currentDeviceOrientation) {
                            currentDeviceOrientation = newOrientation
                            // Only the device-orientation output follows the sensor; the capture
                            // rotation and the ViewPort follow display changes (displayListener).
                            orientationChangedCallback?.invoke(newOrientation)
                        }
                    }
                }
            }
            orientationEventListener?.enable()
        } else {
            orientationEventListener?.disable()
            orientationEventListener = null
        }
    }

    actual fun setTargetOrientation(orientation: DeviceOrientation?) {
        targetOrientation = orientation
        // applyCaptureRotation honors the override when set, and otherwise uses the DISPLAY rotation
        // (consistent with auto mode) — so clearing the override (null) immediately reverts to the
        // display rotation instead of a possibly-stale sensor orientation. Fall back to the sensor
        // orientation only if there's no preview view yet.
        previewView?.let { applyCaptureRotation(it) }
            ?: applyTargetRotation(orientation ?: currentDeviceOrientation)
    }

    private fun applyTargetRotation(orientation: DeviceOrientation) {
        val rotation = when (orientation) {
            DeviceOrientation.PORTRAIT -> Surface.ROTATION_0
            DeviceOrientation.LANDSCAPE_LEFT -> Surface.ROTATION_90
            DeviceOrientation.PORTRAIT_UPSIDE_DOWN -> Surface.ROTATION_180
            DeviceOrientation.LANDSCAPE_RIGHT -> Surface.ROTATION_270
        }
        imageCapture?.targetRotation = rotation
        videoCapture?.targetRotation = rotation
    }

    /**
     * Sets the capture rotation from the current display rotation (auto mode) or the explicit
     * [targetOrientation] override. Keeping capture rotation tied to the display — the same source
     * as the ViewPort and the preview box — guarantees the saved photo's orientation matches what
     * the user saw (#136).
     */
    private fun applyCaptureRotation(previewView: PreviewView) {
        val override = targetOrientation
        if (override != null) {
            applyTargetRotation(override)
            return
        }
        val rotation = currentDisplayRotation(previewView)
        imageCapture?.targetRotation = rotation
        videoCapture?.targetRotation = rotation
    }

    /**
     * Releases the camera. Terminal and idempotent: afterwards no bind reaches CameraX, a capture
     * still in flight returns [ImageCaptureResult.Error], and late CameraX callbacks are ignored.
     * Call on the main thread, like every other CameraX binding call.
     */
    actual fun cleanup() {
        if (!released.compareAndSet(false, true)) return

        orientationEventListener?.disable()
        orientationEventListener = null
        orientationChangedCallback = null
        stopListeningForDisplayChanges()

        // Stop frame delivery before anything it uses goes away, and stop a recording while its
        // VideoCapture is still bound so it finalizes cleanly.
        imageAnalyzer?.let { backend.clearAnalyzer(it) }
        activeRecording?.stop()
        activeRecording = null

        // Release this controller's own use cases (not unbindAll: the process-wide provider may hold
        // another owner's use cases).
        try {
            unbindOwnUseCases()
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Unbinding use cases on release failed", e)
        }

        analyzerConsumers.value = emptyList()
        imageCaptureListeners.clear()
        recordingOutcome?.complete(VideoCaptureResult.Error(controllerReleased()))

        // A capture still in flight would otherwise wait for a callback that is now ignored.
        inFlightCaptures.toList().forEach { continuation ->
            if (inFlightCaptures.remove(continuation)) {
                continuation.resume(ImageCaptureResult.Error(controllerReleased()))
            }
        }

        imageProcessingExecutor.shutdown()
        analyzerExecutor.shutdown()
    }

    private fun controllerReleased() = IllegalStateException("Camera controller was released")

    private companion object {
        const val TAG = "CameraK"
        const val CAMERAK_FOLDER = "CameraK"

        val Directory.isGallery: Boolean get() = this == Directory.PICTURES || this == Directory.DCIM

        val PLAYABLE_FINALIZE_ERRORS = setOf(
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE,
        )

        val Directory.publicDirectoryName: String
            get() = when (this) {
                Directory.PICTURES -> Environment.DIRECTORY_PICTURES
                Directory.DCIM -> Environment.DIRECTORY_DCIM
                Directory.DOCUMENTS -> Environment.DIRECTORY_DOCUMENTS
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
 * Dispatches each camera frame to the [ImageAnalysis.Analyzer] instances [consumers] returns for it.
 * Uses [RefCountedImageProxy] so each analyzer can call `close()` independently;
 * the underlying buffer is released only when all analyzers have finished.
 */
internal class MultiplexingAnalyzer(private val consumers: () -> List<ImageAnalysis.Analyzer>) :
    ImageAnalysis.Analyzer {
    constructor(analyzers: List<ImageAnalysis.Analyzer>) : this({ analyzers })

    override fun analyze(imageProxy: ImageProxy) {
        val analyzers = consumers()
        if (analyzers.isEmpty()) {
            imageProxy.close()
            return
        }
        val refCount = AtomicInteger(analyzers.size)
        for (analyzer in analyzers) {
            val frame = RefCountedImageProxy(imageProxy, refCount)
            // Runs on the analyzer executor, where an escaped exception kills the host process. A
            // failing consumer gives up its share of the frame so the others, and the stream, go on.
            try {
                analyzer.analyze(frame)
            } catch (e: Exception) {
                CameraKLogger.e("CameraK", "Image analyzer failed", e)
                frame.close()
            }
        }
    }
}

/**
 * Wraps an [ImageProxy] with reference-counted close semantics.
 * The real proxy is closed only when the last holder calls [close]; each holder's close counts once.
 *
 * Each holder reads the planes through its own read-only views of CameraX's buffers, so one analyzer
 * reading or trying to write a plane cannot move the position or change the pixels another analyzer
 * sees. [getImage] is still CameraX's shared image: read it, never write to it.
 */
private class RefCountedImageProxy(private val delegate: ImageProxy, private val refCount: AtomicInteger) :
    ImageProxy by delegate {
    private val closed = AtomicBoolean(false)

    private val ownPlanes: Array<ImageProxy.PlaneProxy> by lazy {
        delegate.planes.map { plane -> ReadOnlyPlane(plane) as ImageProxy.PlaneProxy }.toTypedArray()
    }

    override fun getPlanes(): Array<ImageProxy.PlaneProxy> = ownPlanes

    override fun close() {
        if (closed.compareAndSet(false, true) && refCount.decrementAndGet() == 0) {
            delegate.close()
        }
    }
}

/** A read-only view of [plane]'s buffer, with its own position and limit. */
private class ReadOnlyPlane(plane: ImageProxy.PlaneProxy) : ImageProxy.PlaneProxy by plane {
    private val buffer: ByteBuffer = plane.buffer.asReadOnlyBuffer()

    override fun getBuffer(): ByteBuffer = buffer
}

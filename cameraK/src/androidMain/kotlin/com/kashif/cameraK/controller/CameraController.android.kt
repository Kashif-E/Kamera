package com.kashif.cameraK.controller

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Environment
import com.kashif.cameraK.utils.CameraKLogger
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalZeroShutterLag
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import android.view.OrientationEventListener
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.plugins.CameraPlugin
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.utils.InvalidConfigurationException
import com.kashif.cameraK.utils.MemoryManager
import com.kashif.cameraK.video.VideoCaptureResult
import com.kashif.cameraK.video.VideoConfiguration
import com.kashif.cameraK.video.VideoQuality
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android-specific implementation of [CameraController] using CameraX.
 */
actual class CameraController(
    val context: Context,
    val lifecycleOwner: LifecycleOwner,
    internal var flashMode: FlashMode,
    internal var torchMode: TorchMode,
    internal var cameraLens: CameraLens,
    internal var imageFormat: ImageFormat,
    internal var qualityPriority: QualityPrioritization,
    internal var directory: Directory,
    internal var cameraDeviceType: CameraDeviceType,
    internal var returnFilePath: Boolean,
    internal var aspectRatio: AspectRatio,
    internal var plugins: MutableList<CameraPlugin>,
    internal var targetResolution: Pair<Int, Int>? = null,
    internal var mirrorFrontCamera: Boolean = false,
) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null
    private var camera: Camera? = null
    var imageAnalyzer: ImageAnalysis? = null
    private var previewView: PreviewView? = null

    // Multiple analyzer support: plugins register their analyzers here
    private val registeredAnalyzers = mutableListOf<ImageAnalysis.Analyzer>()

    // Video recording
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var recordingOutputFile: File? = null
    private val recordingFinalizeChannel = Channel<VideoCaptureResult>(Channel.CONFLATED)

    private val imageCaptureListeners = mutableListOf<(ByteArray) -> Unit>()

    private val memoryManager = MemoryManager
    private val pendingCaptures = atomic(0)
    private val maxConcurrentCaptures = 3

    private val imageProcessingExecutor = Executors.newFixedThreadPool(2)

    fun bindCamera(previewView: PreviewView, onCameraReady: () -> Unit = {}) {
        this.previewView = previewView

        memoryManager.initialize(context)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                cameraProvider?.unbindAll()

                val resolutionSelector = createResolutionSelector()

                preview = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val cameraSelector = createCameraSelector()

                configureCaptureUseCase(resolutionSelector)
                configureVideoCaptureUseCase()

                val useCaseGroupBuilder = UseCaseGroup.Builder()
                    .addUseCase(preview!!)
                    .addUseCase(imageCapture!!)

                videoCapture?.let { useCaseGroupBuilder.addUseCase(it) }
                imageAnalyzer?.let { useCaseGroupBuilder.addUseCase(it) }

                previewView.viewPort?.let { useCaseGroupBuilder.setViewPort(it) }

                val useCaseGroup = useCaseGroupBuilder.build()

                camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    useCaseGroup,
                )

                onCameraReady()
            } catch (exc: Exception) {
                CameraKLogger.e("CameraK", "==> Use case binding failed for $cameraDeviceType: ${exc.message}")
                CameraKLogger.e("CameraK", "error", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Create a resolution selector based on memory conditions
     */
    private fun createResolutionSelector(): ResolutionSelector {
        memoryManager.updateMemoryStatus()

        return if (targetResolution != null) {
            // When target resolution is set, prioritize it over aspect ratio
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(targetResolution!!.first, targetResolution!!.second),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                )
                .build()
        } else {
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(aspectRatio.toCameraXAspectRatioStrategy())
                .build()
        }
    }

    private fun AspectRatio.toCameraXAspectRatioStrategy(): AspectRatioStrategy = when (this) {
        AspectRatio.RATIO_16_9, AspectRatio.RATIO_9_16 -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        AspectRatio.RATIO_4_3 -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        AspectRatio.RATIO_1_1 -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY // closest available
    }

    /**
     * Creates a camera selector based on lens facing and device type.
     *
     * Uses Camera2 Interop to access physical camera characteristics for proper
     * device type selection (telephoto, ultra-wide, macro). Falls back gracefully
     * to the default camera if the requested type is not available.
     *
     * @return CameraSelector configured for the current lens and device type
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun createCameraSelector(): CameraSelector {
        val builder = CameraSelector.Builder()
            .requireLensFacing(cameraLens.toCameraXLensFacing())

        when (cameraDeviceType) {
            CameraDeviceType.WIDE_ANGLE, CameraDeviceType.DEFAULT -> {
                // Default camera, no filter needed
            }
            CameraDeviceType.TELEPHOTO -> {
                builder.addCameraFilter { cameraInfos ->
                    cameraInfos.filter { cameraInfo ->
                        try {
                            val camera2Info = Camera2CameraInfo.from(cameraInfo)
                            val focalLengths = camera2Info.getCameraCharacteristic(
                                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
                            )?.toList() ?: emptyList()
                            focalLengths.any { it > 4.0f }
                        } catch (e: Exception) {
                            false
                        }
                    }.ifEmpty {
                        CameraKLogger.w("CameraK", "Telephoto camera not available, using default")
                        cameraInfos.take(1)
                    }
                }
            }
            CameraDeviceType.ULTRA_WIDE -> {
                builder.addCameraFilter { cameraInfos ->
                    cameraInfos.filter { cameraInfo ->
                        try {
                            val camera2Info = Camera2CameraInfo.from(cameraInfo)
                            val focalLengths = camera2Info.getCameraCharacteristic(
                                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
                            )?.toList() ?: emptyList()
                            focalLengths.any { it < 2.5f }
                        } catch (e: Exception) {
                            false
                        }
                    }.ifEmpty {
                        CameraKLogger.w("CameraK", "Ultra-wide camera not available, using default")
                        cameraInfos.take(1)
                    }
                }
            }
            CameraDeviceType.MACRO -> {
                builder.addCameraFilter { cameraInfos ->
                    cameraInfos.filter { cameraInfo ->
                        try {
                            val camera2Info = Camera2CameraInfo.from(cameraInfo)
                            val minFocusDistance = camera2Info.getCameraCharacteristic(
                                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE,
                            ) ?: 0f
                            minFocusDistance > 0f && minFocusDistance < 0.2f
                        } catch (e: Exception) {
                            false
                        }
                    }.ifEmpty {
                        CameraKLogger.w("CameraK", "Macro camera not available, using default")
                        cameraInfos.take(1)
                    }
                }
            }
        }

        return builder.build()
    }

    /**
     * Configure the image capture use case with settings adapted to current memory conditions
     */
    @OptIn(ExperimentalZeroShutterLag::class)
    private fun configureCaptureUseCase(resolutionSelector: ResolutionSelector) {
        imageCapture = ImageCapture.Builder()
            .setFlashMode(flashMode.toCameraXFlashMode())
            .setCaptureMode(
                when (qualityPriority) {
                    QualityPrioritization.QUALITY -> ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                    QualityPrioritization.SPEED -> ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
                    QualityPrioritization.BALANCED -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                    QualityPrioritization.NONE -> {
                        if (memoryManager.isUnderMemoryPressure()) {
                            ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                        } else {
                            ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                        }
                    }
                },
            )
            .setResolutionSelector(resolutionSelector)
            .build()
    }

    fun updateImageAnalyzer() {
        camera?.let {
            cameraProvider?.unbind(imageAnalyzer)
            imageAnalyzer?.let { analyzer ->
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.Builder().requireLensFacing(cameraLens.toCameraXLensFacing())
                        .build(),
                    analyzer,
                )
            }
        } ?: throw InvalidConfigurationException("Camera not initialized.")
    }

    /**
     * Registers an [ImageAnalysis.Analyzer] to receive camera frames.
     * Multiple analyzers can be registered simultaneously — they share a single
     * [ImageAnalysis] use case via ref-counted frame dispatch.
     */
    fun registerImageAnalyzer(analyzer: ImageAnalysis.Analyzer) {
        registeredAnalyzers.add(analyzer)
        rebuildMultiplexedAnalyzer()
    }

    /**
     * Unregisters a previously registered analyzer.
     */
    fun unregisterImageAnalyzer(analyzer: ImageAnalysis.Analyzer) {
        registeredAnalyzers.remove(analyzer)
        rebuildMultiplexedAnalyzer()
    }

    private fun rebuildMultiplexedAnalyzer() {
        if (registeredAnalyzers.isEmpty()) {
            if (imageAnalyzer != null) {
                try { cameraProvider?.unbind(imageAnalyzer) } catch (_: Exception) {}
                imageAnalyzer = null
            }
            return
        }

        val composite = MultiplexingAnalyzer(registeredAnalyzers.toList())
        imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply {
                setAnalyzer(ContextCompat.getMainExecutor(context), composite)
            }

        try {
            updateImageAnalyzer()
        } catch (_: Exception) {
            // Camera may not be initialized yet; will be bound at bindCamera time
        }
    }

    actual suspend fun takePictureToFile(): ImageCaptureResult = suspendCancellableCoroutine { cont ->
        if (pendingCaptures.incrementAndGet() > maxConcurrentCaptures) {
            pendingCaptures.decrementAndGet()
            CameraKLogger.w("CameraK", "Burst queue full, dropping frame")
            cont.resume(ImageCaptureResult.Error(Exception("Burst queue full, capture rejected")))
            return@suspendCancellableCoroutine
        }

        performCaptureToFile(cont)

        cont.invokeOnCancellation {
            pendingCaptures.decrementAndGet()
        }
    }

    /**
     * Capture metadata: mirror the saved image horizontally for the front camera when configured,
     * so the photo matches the mirrored preview (#112).
     */
    private fun captureMetadata(): ImageCapture.Metadata =
        ImageCapture.Metadata().apply {
            isReversedHorizontal = mirrorFrontCamera && cameraLens == CameraLens.FRONT
        }

    /**
     * Perform fast file-based capture without ByteArray processing.
     * Directly saves to final destination and returns file path.
     */
    private fun performCaptureToFile(continuation: CancellableContinuation<ImageCaptureResult>) {
        // Create final output file directly in desired directory
        val outputFile = createFinalOutputFile()
        val outputOptions =
            ImageCapture.OutputFileOptions.Builder(outputFile).setMetadata(captureMetadata()).build()

        imageCapture?.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    pendingCaptures.decrementAndGet()

                    // Notify MediaStore so image appears in Gallery
                    notifyMediaStore(outputFile)

                    continuation.resume(ImageCaptureResult.SuccessWithFile(outputFile.absolutePath))
                }

                override fun onError(exc: ImageCaptureException) {
                    CameraKLogger.e("CameraK", "Image capture failed: ${exc.message}", exc)
                    pendingCaptures.decrementAndGet()
                    outputFile.delete() // Clean up failed capture file
                    continuation.resume(ImageCaptureResult.Error(exc))
                }
            },
        ) ?: run {
            pendingCaptures.decrementAndGet()
            continuation.resume(ImageCaptureResult.Error(Exception("ImageCapture use case is not initialized.")))
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

    actual fun toggleTorchMode() {
        torchMode = when (torchMode) {
            TorchMode.OFF -> TorchMode.ON
            TorchMode.ON -> TorchMode.AUTO
            TorchMode.AUTO -> TorchMode.OFF
        }
        // CameraX doesn't support AUTO torch mode, treat it as ON
        val enableTorch = torchMode == TorchMode.ON || torchMode == TorchMode.AUTO
        if (torchMode == TorchMode.AUTO) {
            CameraKLogger.w("CameraK", "TorchMode.AUTO not natively supported, using ON")
        }
        camera?.cameraControl?.enableTorch(enableTorch)
    }

    actual fun setTorchMode(mode: TorchMode) {
        torchMode = mode
        // CameraX doesn't support AUTO torch mode, treat it as ON
        val enableTorch = mode == TorchMode.ON || mode == TorchMode.AUTO
        if (mode == TorchMode.AUTO) {
            CameraKLogger.w("CameraK", "TorchMode.AUTO not natively supported, using ON")
        }
        camera?.cameraControl?.enableTorch(enableTorch)
    }

    actual fun setZoom(zoomRatio: Float) {
        camera?.cameraControl?.setZoomRatio(zoomRatio.coerceIn(1f, getMaxZoom()))
    }

    actual fun getZoom(): Float = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f

    actual fun getMaxZoom(): Float = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f

    actual fun getTorchMode(): TorchMode? = torchMode

    actual fun toggleCameraLens() {
        memoryManager.updateMemoryStatus()

        if (memoryManager.isUnderMemoryPressure()) {
            memoryManager.clearBufferPools()
            System.gc()
        }

        cameraLens = if (cameraLens == CameraLens.BACK) CameraLens.FRONT else CameraLens.BACK
        previewView?.let { bindCamera(it) }
    }

    actual fun getCameraLens(): CameraLens? = cameraLens

    actual fun getImageFormat(): ImageFormat = imageFormat

    actual fun getQualityPrioritization(): QualityPrioritization = qualityPriority

    actual fun getPreferredCameraDeviceType(): CameraDeviceType = cameraDeviceType

    actual fun setPreferredCameraDeviceType(deviceType: CameraDeviceType) {
        cameraDeviceType = deviceType
    }

    actual fun startSession() {
        memoryManager.updateMemoryStatus()
        memoryManager.clearBufferPools()
        initializeControllerPlugins()
    }

    actual fun stopSession() {
        cameraProvider?.unbindAll()
        memoryManager.clearBufferPools()
    }

    actual fun addImageCaptureListener(listener: (ByteArray) -> Unit) {
        imageCaptureListeners.add(listener)
    }

    actual fun initializeControllerPlugins() {
        plugins.forEach { it.initialize(this) }
    }

    private fun createTempFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        // Use configured directory
        val storageDir = when (directory) {
            Directory.PICTURES -> android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_PICTURES,
            )
            Directory.DCIM -> android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DCIM,
            )
            Directory.DOCUMENTS -> context.getExternalFilesDir(null) ?: context.filesDir
        }

        // Create CameraK subdirectory
        val cameraKDir = File(storageDir, "CameraK")
        if (!cameraKDir.exists()) {
            cameraKDir.mkdirs()
        }

        return File(
            cameraKDir,
            "IMG_$timeStamp.${imageFormat.extension}",
        )
    }

    /**
     * Creates final output file for direct capture (used by takePictureToFile).
     * Same as createTempFile but semantically represents the final destination.
     */
    private fun createFinalOutputFile(): File = createTempFile()

    /**
     * Notifies Android's MediaStore about a new image file so it appears in Gallery.
     * Uses MediaScannerConnection for compatibility across Android versions.
     */
    private fun notifyMediaStore(file: File) {
        try {
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf(
                    when (imageFormat) {
                        ImageFormat.JPEG -> "image/jpeg"
                        ImageFormat.PNG -> "image/png"
                    },
                ),
                null,
            )
        } catch (e: Exception) {
            CameraKLogger.e("CameraK", "Failed to notify MediaStore: ${e.message}")
        }
    }

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

    @android.annotation.SuppressLint("MissingPermission")
    actual suspend fun startRecording(configuration: VideoConfiguration): String = suspendCancellableCoroutine { cont ->
        val vc = videoCapture ?: run {
            cont.resumeWithException(IllegalStateException("VideoCapture use case not available"))
            return@suspendCancellableCoroutine
        }

        val outputFile = createVideoOutputFile(configuration)
        recordingOutputFile = outputFile
        val outputOptions = FileOutputOptions.Builder(outputFile).build()

        val hasAudioPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        val pendingRecording = vc.output.prepareRecording(context, outputOptions).apply {
            if (configuration.enableAudio && hasAudioPermission) {
                withAudioEnabled()
            }
        }

        activeRecording = pendingRecording.start(
            ContextCompat.getMainExecutor(context),
        ) { event ->
            when (event) {
                is VideoRecordEvent.Finalize -> {
                    val file = recordingOutputFile
                    if (event.hasError()) {
                        recordingFinalizeChannel.trySend(
                            VideoCaptureResult.Error(
                                Exception("Recording error code: ${event.error}"),
                            ),
                        )
                    } else {
                        // Notify MediaStore so video appears in Gallery
                        file?.let { notifyMediaStoreVideo(it) }
                        recordingFinalizeChannel.trySend(
                            VideoCaptureResult.Success(
                                filePath = file?.absolutePath ?: "",
                                durationMs = event.recordingStats.recordedDurationNanos / 1_000_000,
                            ),
                        )
                    }
                    recordingOutputFile = null
                }
            }
        }

        cont.resume(outputFile.absolutePath)
    }

    actual suspend fun stopRecording(): VideoCaptureResult {
        val recording = activeRecording ?: return VideoCaptureResult.Error(
            IllegalStateException("No active recording"),
        )
        recording.stop()
        activeRecording = null
        return recordingFinalizeChannel.receive()
    }

    actual suspend fun pauseRecording() {
        activeRecording?.pause()
    }

    actual suspend fun resumeRecording() {
        activeRecording?.resume()
    }

    private fun createVideoOutputFile(config: VideoConfiguration): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = if (config.outputDirectory != null) {
            File(config.outputDirectory).also { it.mkdirs() }
        } else {
            val storageDir = when (directory) {
                Directory.PICTURES, Directory.DCIM -> Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MOVIES,
                )
                Directory.DOCUMENTS -> context.getExternalFilesDir(null) ?: context.filesDir
            }
            File(storageDir, "CameraK").also { it.mkdirs() }
        }
        return File(dir, "${config.filePrefix}_$timeStamp.mp4")
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
            CameraKLogger.e("CameraK", "Failed to notify MediaStore for video: ${e.message}")
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
                            // Update capture rotation when in auto mode
                            if (targetOrientation == null) {
                                applyTargetRotation(newOrientation)
                            }
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
        applyTargetRotation(orientation ?: currentDeviceOrientation)
    }

    private fun applyTargetRotation(orientation: DeviceOrientation) {
        val rotation = when (orientation) {
            DeviceOrientation.PORTRAIT -> android.view.Surface.ROTATION_0
            DeviceOrientation.LANDSCAPE_LEFT -> android.view.Surface.ROTATION_90
            DeviceOrientation.PORTRAIT_UPSIDE_DOWN -> android.view.Surface.ROTATION_180
            DeviceOrientation.LANDSCAPE_RIGHT -> android.view.Surface.ROTATION_270
        }
        imageCapture?.targetRotation = rotation
        videoCapture?.targetRotation = rotation
    }

    /**
     * Clean up resources when no longer needed
     * Should be called when the controller is being destroyed
     */
    actual fun cleanup() {
        orientationEventListener?.disable()
        orientationEventListener = null
        orientationChangedCallback = null
        activeRecording?.stop()
        activeRecording = null
        registeredAnalyzers.clear()
        recordingFinalizeChannel.close()
        imageProcessingExecutor.shutdown()
        memoryManager.clearBufferPools()
    }
}

/**
 * Dispatches each camera frame to multiple [ImageAnalysis.Analyzer] instances.
 * Uses [RefCountedImageProxy] so each analyzer can call `close()` independently;
 * the underlying buffer is released only when all analyzers have finished.
 */
private class MultiplexingAnalyzer(
    private val analyzers: List<ImageAnalysis.Analyzer>,
) : ImageAnalysis.Analyzer {
    override fun analyze(imageProxy: ImageProxy) {
        if (analyzers.isEmpty()) {
            imageProxy.close()
            return
        }
        if (analyzers.size == 1) {
            analyzers[0].analyze(imageProxy)
            return
        }
        val refCount = java.util.concurrent.atomic.AtomicInteger(analyzers.size)
        for (analyzer in analyzers) {
            analyzer.analyze(RefCountedImageProxy(imageProxy, refCount))
        }
    }
}

/**
 * Wraps an [ImageProxy] with reference-counted close semantics.
 * The real proxy is closed only when the last holder calls [close].
 */
private class RefCountedImageProxy(
    private val delegate: ImageProxy,
    private val refCount: java.util.concurrent.atomic.AtomicInteger,
) : ImageProxy by delegate {
    override fun close() {
        if (refCount.decrementAndGet() <= 0) {
            delegate.close()
        }
    }
}

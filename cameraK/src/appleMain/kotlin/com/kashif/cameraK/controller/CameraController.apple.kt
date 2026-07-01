package com.kashif.cameraK.controller

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
import com.kashif.cameraK.utils.MemoryManager
import com.kashif.cameraK.utils.fixOrientation
import com.kashif.cameraK.utils.toByteArray
import com.kashif.cameraK.utils.toUIImage
import com.kashif.cameraK.video.VideoCaptureResult
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.autoreleasepool
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureFlashMode
import platform.AVFoundation.AVCaptureFlashModeAuto
import platform.AVFoundation.AVCaptureFlashModeOff
import platform.AVFoundation.AVCaptureFlashModeOn
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureMovieFileOutput
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureTorchMode
import platform.AVFoundation.AVCaptureTorchModeAuto
import platform.AVFoundation.AVCaptureTorchModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
import platform.AVFoundation.AVCaptureVideoOrientation
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeLeft
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeRight
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVCaptureVideoOrientationPortraitUpsideDown
import platform.AVFoundation.AVMediaTypeAudio
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIDeviceOrientationDidChangeNotification
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIViewController
import platform.darwin.DISPATCH_QUEUE_PRIORITY_HIGH
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

actual class CameraController(
    internal var flashMode: FlashMode,
    internal var torchMode: TorchMode,
    internal var cameraLens: CameraLens,
    internal var imageFormat: ImageFormat,
    internal var qualityPriority: QualityPrioritization,
    internal var directory: Directory,
    internal var cameraDeviceType: CameraDeviceType,
    internal var aspectRatio: AspectRatio,
    internal var targetResolution: Pair<Int, Int>? = null,
    internal var mirrorFrontCamera: Boolean = false,
) : UIViewController(null, null) {
    private var isCapturing = atomic(false)
    private val customCameraController = CustomCameraController(
        qualityPrioritization = qualityPriority,
        initialCameraLens = cameraLens,
        aspectRatio = aspectRatio,
        targetResolution = targetResolution,
        mirrorFrontCamera = mirrorFrontCamera,
    )
    private var imageCaptureListeners = mutableListOf<(ByteArray) -> Unit>()
    private var metadataOutput = AVCaptureMetadataOutput()
    private var metadataObjectsDelegate: AVCaptureMetadataOutputObjectsDelegateProtocol? = null

    // Video recording
    private var movieFileOutput: AVCaptureMovieFileOutput? = null
    private var videoRecordingDelegate: VideoRecordingDelegate? = null
    private var videoOutputFilePath: String? = null

    private val memoryManager = MemoryManager
    private val pendingCaptures = atomic(0)
    private val maxConcurrentCaptures = 3

    override fun viewDidLoad() {
        super.viewDidLoad()

        memoryManager.initialize()
        setupCamera()
    }

    override fun viewWillAppear(animated: Boolean) {
        super.viewWillAppear(animated)
        memoryManager.updateMemoryStatus()
    }

    override fun viewDidDisappear(animated: Boolean) {
        super.viewDidDisappear(animated)

        memoryManager.clearBufferPools()
    }

    fun getCameraPreviewLayer() = customCameraController.cameraPreviewLayer

    /**
     * Returns whether the capture session is ready for use.
     * Used by plugins to check if they can add outputs.
     */
    fun isSessionReady(): Boolean = customCameraController.captureSession != null

    /**
     * Queues a configuration change to be applied atomically (plugin outputs, etc).
     * Used by plugins (OCR, QRScanner) to safely add outputs without crashes.
     */
    fun queueConfigurationChange(change: () -> Unit) {
        customCameraController.queueConfigurationChange(change)
    }

    /**
     * Safely adds an output within the queued configuration block.
     */
    fun safeAddOutput(output: AVCaptureOutput) {
        customCameraController.safeAddOutput(output)
    }

    /**
     * Safely removes an output previously added via [safeAddOutput].
     */
    fun safeRemoveOutput(output: AVCaptureOutput) {
        customCameraController.safeRemoveOutput(output)
    }

    @Volatile
    private var lastVideoOrientation: AVCaptureVideoOrientation = AVCaptureVideoOrientationPortrait

    internal fun currentVideoOrientation(): AVCaptureVideoOrientation {
        // FaceUp / FaceDown / Unknown don't correspond to a video orientation; mapping them to
        // Portrait distorts a landscape preview when the device lies flat (#115). Keep the last
        // valid orientation so portrait/landscape tracking stays stable as the device tilts (#109).
        lastVideoOrientation = when (UIDevice.currentDevice.orientation) {
            UIDeviceOrientation.UIDeviceOrientationPortrait -> AVCaptureVideoOrientationPortrait
            UIDeviceOrientation.UIDeviceOrientationPortraitUpsideDown -> AVCaptureVideoOrientationPortraitUpsideDown
            UIDeviceOrientation.UIDeviceOrientationLandscapeLeft -> AVCaptureVideoOrientationLandscapeRight
            UIDeviceOrientation.UIDeviceOrientationLandscapeRight -> AVCaptureVideoOrientationLandscapeLeft
            else -> lastVideoOrientation
        }
        return lastVideoOrientation
    }

    private fun setupCamera() {
        customCameraController.onSessionReady = {
            try {
                customCameraController.setupPreviewLayer(view)
            } catch (e: Exception) {
                CameraKLogger.e("CameraK", "CameraK Error: setupPreviewLayer - ${e.message}")
            }

            try {
                if (customCameraController.captureSession?.canAddOutput(metadataOutput) == true) {
                    customCameraController.captureSession?.addOutput(metadataOutput)
                }
            } catch (e: Exception) {
                CameraKLogger.e("CameraK", "CameraK Error: metadata output - ${e.message}")
            }

            // Add movie file output for video recording
            try {
                val movieOutput = AVCaptureMovieFileOutput()
                if (customCameraController.captureSession?.canAddOutput(movieOutput) == true) {
                    customCameraController.captureSession?.addOutput(movieOutput)
                    movieFileOutput = movieOutput
                }
            } catch (e: Exception) {
                CameraKLogger.e("CameraK", "CameraK Error: movie output - ${e.message}")
            }

            startSession()
        }

        try {
            customCameraController.setupSession(cameraDeviceType)
        } catch (e: Exception) {
            CameraKLogger.e("CameraK", "CameraK Error: setupSession - ${e.message}")
            return
        }

        customCameraController.onPhotoCapture = { image ->
            image?.let {
                processImageCapture(it)
            }
        }

        customCameraController.onError = { error ->
            CameraKLogger.e("CameraK", "CameraK Error: $error")
        }
    }

    private fun writeDataToFile(data: NSData, filePath: String): Boolean =
        NSFileManager.defaultManager.createFileAtPath(filePath, data, null)

    private fun processImageCapture(imageData: NSData) {
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH.toLong(), 0u)) {
            autoreleasepool {
                memoryManager.updateMemoryStatus()

                try {
                    val estimatedSize = imageData.length.toInt()
                    val buffer = if (estimatedSize > 0) {
                        memoryManager.getBuffer(estimatedSize)
                    } else {
                        ByteArray(imageData.length.toInt())
                    }

                    val data = imageData.toByteArray(reuseBuffer = buffer)

                    dispatch_async(dispatch_get_main_queue()) {
                        imageCaptureListeners.forEach { it(data) }
                    }

                    if (buffer.size >= estimatedSize) {
                        memoryManager.recycleBuffer(buffer)
                    }
                } catch (e: Exception) {
                    CameraKLogger.e("CameraK", "CameraK: Error processing image data: ${e.message ?: "Unknown error"}")
                }
            }
        }
    }

    fun setMetadataObjectsDelegate(delegate: AVCaptureMetadataOutputObjectsDelegateProtocol) {
        metadataObjectsDelegate = delegate
        metadataOutput.setMetadataObjectsDelegate(delegate, dispatch_get_main_queue())
    }

    /**
     * Clears the metadata delegate so QR/barcode callbacks stop. Used by the QR plugin on detach.
     */
    fun clearMetadataObjectsDelegate() {
        metadataObjectsDelegate = null
        metadataOutput.setMetadataObjectsDelegate(null, dispatch_get_main_queue())
    }

    fun updateMetadataObjectTypes(newTypes: List<String>) {
        // Only ever assign the intersection with availableMetadataObjectTypes. Assigning a type the
        // hardware doesn't advertise throws NSInvalidArgumentException (SIGABRT) inside
        // _buildAndRunGraph — most visible on Mac Catalyst, where the camera advertises far fewer
        // barcode types than an iPhone (and sometimes none). Never force-set the full list. (#70)
        val available = metadataOutput.availableMetadataObjectTypes
        val supportedTypes = newTypes.filter { available.contains(it) }
        // Nothing supported yet: the output isn't connected, or the platform (some Macs) advertises
        // no barcode metadata types at all. Leave types unset — scanning is a no-op, not a crash.
        // (A Kotlin try/catch can't rescue this: an NSException from the setter aborts the process,
        // so the filter — never assigning an unavailable type — IS the guard.)
        if (supportedTypes.isEmpty()) return
        metadataOutput.metadataObjectTypes = supportedTypes
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        customCameraController.cameraPreviewLayer?.setFrame(view.bounds)
    }

    /**
     * Fast file-based capture for iOS - saves directly without ByteArray processing.
     */
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    actual suspend fun takePictureToFile(): ImageCaptureResult = suspendCancellableCoroutine { continuation ->
        if (pendingCaptures.incrementAndGet() > maxConcurrentCaptures) {
            pendingCaptures.decrementAndGet()
            CameraKLogger.e("CameraK", "CameraK: Burst queue full, dropping frame")
            continuation.resume(ImageCaptureResult.Error(Exception("Burst queue full, capture rejected")))
            return@suspendCancellableCoroutine
        }

        if (!isCapturing.compareAndSet(expect = false, update = true)) {
            pendingCaptures.decrementAndGet()
            continuation.resume(ImageCaptureResult.Error(Exception("Capture in progress")))
            return@suspendCancellableCoroutine
        }

        val captureHandler = object {
            var completed = false

            fun process(image: NSData?, error: String?) {
                if (completed) return
                completed = true

                if (image != null) {
                    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH.toLong(), 0u)) {
                        try {
                            autoreleasepool {
                                val result = try {
                                    val filePath = createTempFile()
                                    val writeSuccess = if (imageFormat == ImageFormat.JPEG) {
                                        writeDataToFile(image, filePath)
                                    } else {
                                        val uiImage = image.toUIImage()
                                        val orientedImage = uiImage.fixOrientation()
                                        val pngData = UIImagePNGRepresentation(orientedImage)
                                        if (pngData != null) writeDataToFile(pngData, filePath) else false
                                    }

                                    if (writeSuccess) {
                                        when (directory) {
                                            Directory.PICTURES, Directory.DCIM -> {
                                                val photosPath = saveToPhotosLibrary(filePath, image)
                                                NSFileManager.defaultManager.removeItemAtPath(
                                                    filePath,
                                                    null,
                                                )

                                                if (photosPath != null) {
                                                    ImageCaptureResult.SuccessWithFile(photosPath)
                                                } else {
                                                    ImageCaptureResult.Error(
                                                        Exception("Failed to save to Photos library"),
                                                    )
                                                }
                                            }
                                            Directory.DOCUMENTS -> {
                                                ImageCaptureResult.SuccessWithFile(filePath)
                                            }
                                        }
                                    } else {
                                        ImageCaptureResult.Error(Exception("Failed to write image to file"))
                                    }
                                } catch (e: Exception) {
                                    CameraKLogger.e("CameraK", "CameraK: File capture error: ${e.message ?: "Unknown"}")
                                    ImageCaptureResult.Error(e)
                                }

                                dispatch_async(dispatch_get_main_queue()) {
                                    isCapturing.value = false
                                    pendingCaptures.decrementAndGet()
                                    continuation.resume(result)
                                }
                            }
                        } catch (e: Exception) {
                            dispatch_async(dispatch_get_main_queue()) {
                                isCapturing.value = false
                                pendingCaptures.decrementAndGet()
                                continuation.resume(ImageCaptureResult.Error(e))
                            }
                        }
                    }
                } else {
                    isCapturing.value = false
                    pendingCaptures.decrementAndGet()
                    continuation.resume(ImageCaptureResult.Error(Exception(error ?: "Capture failed")))
                }
            }
        }

        customCameraController.onPhotoCapture = { image ->
            captureHandler.process(image, null)
        }

        customCameraController.onError = { error ->
            captureHandler.process(null, error.toString())
        }

        continuation.invokeOnCancellation {
            isCapturing.value = false
            pendingCaptures.decrementAndGet()
            captureHandler.process(null, "Capture cancelled")
        }

        customCameraController.captureImage()
    }

    actual fun toggleFlashMode() {
        flashMode = when (flashMode) {
            FlashMode.OFF -> FlashMode.ON
            FlashMode.ON -> FlashMode.AUTO
            FlashMode.AUTO -> FlashMode.OFF
        }
        customCameraController.setFlashMode(flashMode.toAVCaptureFlashMode())
    }

    actual fun setFlashMode(mode: FlashMode) {
        flashMode = mode
        customCameraController.setFlashMode(mode.toAVCaptureFlashMode())
    }

    actual fun getFlashMode(): FlashMode? {
        fun AVCaptureFlashMode.toCameraKFlashMode(): FlashMode? = when (this) {
            AVCaptureFlashModeOn -> FlashMode.ON
            AVCaptureFlashModeOff -> FlashMode.OFF
            AVCaptureFlashModeAuto -> FlashMode.AUTO
            else -> null
        }

        return customCameraController.flashMode.toCameraKFlashMode()
    }

    actual fun toggleTorchMode() {
        torchMode = when (torchMode) {
            TorchMode.OFF -> TorchMode.ON
            TorchMode.ON -> TorchMode.AUTO
            TorchMode.AUTO -> TorchMode.OFF
        }
        customCameraController.setTorchMode(torchMode.toAVCaptureTorchMode())
    }

    actual fun setTorchMode(mode: TorchMode) {
        torchMode = mode
        customCameraController.setTorchMode(mode.toAVCaptureTorchMode())
    }

    actual fun getTorchMode(): TorchMode? = torchMode

    actual fun setFocus(x: Float, y: Float, size: Float) {
        customCameraController.setFocus(x, y)
    }
    actual fun setZoom(zoomRatio: Float) {
        customCameraController.setZoom(zoomRatio)
    }

    actual fun getZoom(): Float = customCameraController.getZoom()

    actual fun getMaxZoom(): Float = customCameraController.getMaxZoom()

    actual fun toggleCameraLens() {
        memoryManager.updateMemoryStatus()

        if (memoryManager.isUnderMemoryPressure()) {
            memoryManager.clearBufferPools()
        }

        cameraLens = if (cameraLens == CameraLens.BACK) CameraLens.FRONT else CameraLens.BACK
        customCameraController.switchCamera()
    }

    actual fun getCameraLens(): CameraLens? = cameraLens

    actual fun getImageFormat(): ImageFormat = imageFormat

    actual fun getAspectRatio(): AspectRatio = aspectRatio

    actual fun getQualityPrioritization(): QualityPrioritization = qualityPriority

    actual fun getPreferredCameraDeviceType(): CameraDeviceType = cameraDeviceType

    actual fun setPreferredCameraDeviceType(deviceType: CameraDeviceType) {
        if (cameraDeviceType == deviceType) return // avoid a redundant AVCaptureSession input swap
        cameraDeviceType = deviceType
        customCameraController.switchToDeviceType(deviceType)
    }

    actual fun startSession() {
        // Note: The actual session start happens in onSessionReady callback (setupCamera).
        // This method is called from CameraKStateHolder.initialize() which may be before
        // viewDidLoad() triggers setupCamera(). The session will start automatically
        // when ready, so we only need to handle the case where session IS ready.
        if (customCameraController.captureSession != null) {
            memoryManager.clearBufferPools()
            customCameraController.startSession()
        }
        // If captureSession is null, onSessionReady will call startSession() when ready
    }

    actual fun stopSession() {
        customCameraController.stopSession()
    }

    actual fun addImageCaptureListener(listener: (ByteArray) -> Unit) {
        imageCaptureListeners.add(listener)
    }

    actual fun removeImageCaptureListener(listener: (ByteArray) -> Unit) {
        imageCaptureListeners.remove(listener)
    }

    actual fun cleanup() {
        removeOrientationObserver()
        orientationChangedCallback = null
        movieFileOutput = null
        videoRecordingDelegate = null
        customCameraController.cleanupSession()
        memoryManager.clearBufferPools()
    }

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun startRecording(configuration: VideoConfiguration): String = suspendCancellableCoroutine { cont ->
        val output = movieFileOutput ?: run {
            cont.resumeWithException(IllegalStateException("Movie file output not available"))
            return@suspendCancellableCoroutine
        }

        if (output.isRecording()) {
            cont.resume(videoOutputFilePath ?: "")
            return@suspendCancellableCoroutine
        }

        if (configuration.enableAudio) {
            addAudioInputIfNeeded()
        }

        val filePath = createVideoTempFile(configuration)
        videoOutputFilePath = filePath
        val fileURL = NSURL.fileURLWithPath(filePath)

        // Delete existing file at path if any
        NSFileManager.defaultManager.removeItemAtPath(filePath, null)

        val delegate = VideoRecordingDelegate()
        videoRecordingDelegate = delegate

        // Set video orientation on the movie file output connection. Use the effective
        // orientation so a locked orientation (setTargetOrientation) is honored for recording.
        output.connectionWithMediaType(platform.AVFoundation.AVMediaTypeVideo)?.let { connection ->
            if (connection.isVideoOrientationSupported()) {
                connection.videoOrientation = effectiveVideoOrientation()
            }
        }

        dispatch_async(dispatch_get_main_queue()) {
            output.startRecordingToOutputFileURL(fileURL, recordingDelegate = delegate)
        }

        cont.resume(filePath)
    }

    actual suspend fun stopRecording(): VideoCaptureResult = suspendCancellableCoroutine { cont ->
        val output = movieFileOutput
        val delegate = videoRecordingDelegate

        if (output == null || delegate == null || !output.isRecording()) {
            cont.resume(VideoCaptureResult.Error(Exception("No active recording")))
            return@suspendCancellableCoroutine
        }

        delegate.onFinished = { result ->
            if (result is VideoCaptureResult.Success) {
                // Save to Photos library when using default directory (PICTURES/DCIM)
                val savedResult = saveVideoToPhotosLibrary(result)
                cont.resume(savedResult)
            } else {
                cont.resume(result)
            }
        }

        dispatch_async(dispatch_get_main_queue()) {
            output.stopRecording()
        }
    }

    // Known limitation: AVCaptureMovieFileOutput does not support true pause/resume — these base
    // AVCaptureFileOutput calls are effectively no-ops, so the file keeps recording continuously
    // while the UI shows "paused". Real pause would require segmented recording + concatenation.
    actual suspend fun pauseRecording() {
        movieFileOutput?.pauseRecording()
    }

    actual suspend fun resumeRecording() {
        movieFileOutput?.resumeRecording()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun addAudioInputIfNeeded() {
        val session = customCameraController.captureSession ?: return
        // Check if audio input already exists
        val hasAudioInput = session.inputs.any { input ->
            val deviceInput = input as? AVCaptureDeviceInput
            deviceInput?.device?.hasMediaType(AVMediaTypeAudio) == true
        }
        if (hasAudioInput) return

        val audioDevice = platform.AVFoundation.AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeAudio)
            ?: return
        val audioInput = AVCaptureDeviceInput.deviceInputWithDevice(audioDevice, null) ?: return

        customCameraController.queueConfigurationChange {
            if (session.canAddInput(audioInput)) {
                session.addInput(audioInput)
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun createVideoTempFile(config: VideoConfiguration): String {
        val timestamp = NSDate().timeIntervalSince1970.toLong()
        val fileName = "${config.filePrefix}_$timestamp.mp4"

        return if (config.outputDirectory != null) {
            val dir = config.outputDirectory
            val fileManager = NSFileManager.defaultManager
            if (!fileManager.fileExistsAtPath(dir)) {
                fileManager.createDirectoryAtPath(
                    dir,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null,
                )
            }
            "$dir/$fileName"
        } else {
            val tempDir = platform.Foundation.NSTemporaryDirectory()
            "$tempDir$fileName"
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun createTempFile(): String {
        val timestamp = Random.nextLong()
        val fileName = "IMG_$timestamp.${imageFormat.extension}"

        return when (directory) {
            Directory.PICTURES, Directory.DCIM -> {
                // For Photos library, use temp directory first
                val tempDir = platform.Foundation.NSTemporaryDirectory()
                "$tempDir$fileName"
            }
            Directory.DOCUMENTS -> {
                // For Documents, create permanent location
                val documentsDir = platform.Foundation.NSSearchPathForDirectoriesInDomains(
                    platform.Foundation.NSDocumentDirectory,
                    platform.Foundation.NSUserDomainMask,
                    true,
                ).firstOrNull() as? String ?: platform.Foundation.NSTemporaryDirectory()

                // Create CameraK subdirectory
                val cameraKDir = "$documentsDir/CameraK"
                val fileManager = NSFileManager.defaultManager
                if (!fileManager.fileExistsAtPath(cameraKDir)) {
                    fileManager.createDirectoryAtPath(
                        cameraKDir,
                        withIntermediateDirectories = true,
                        attributes = null,
                        error = null,
                    )
                }

                "$cameraKDir/$fileName"
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun saveToPhotosLibrary(filePath: String, imageData: NSData): String? {
        var savedAssetId: String? = null
        var saveError: String? = null

        // Save to Photos library synchronously (blocking the capture flow)
        val semaphore = platform.darwin.dispatch_semaphore_create(0)

        PHPhotoLibrary.sharedPhotoLibrary().performChanges(
            changeBlock = {
                val creationRequest = PHAssetChangeRequest.creationRequestForAssetFromImageAtFileURL(
                    NSURL.fileURLWithPath(filePath),
                )
                savedAssetId = creationRequest?.placeholderForCreatedAsset?.localIdentifier
            },
            completionHandler = { success, error ->
                if (!success || error != null) {
                    saveError = error?.localizedDescription ?: "Failed to save to Photos"
                    CameraKLogger.e("CameraK", "CameraK: Failed to save to Photos: ${saveError ?: "Unknown error"}")
                }
                platform.darwin.dispatch_semaphore_signal(semaphore)
            },
        )

        platform.darwin.dispatch_semaphore_wait(semaphore, platform.darwin.DISPATCH_TIME_FOREVER)

        // Return the asset identifier or error
        return if (saveError == null && savedAssetId != null) {
            "ph://$savedAssetId" // Use custom scheme to indicate Photos library asset
        } else {
            null
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun saveVideoToPhotosLibrary(result: VideoCaptureResult.Success): VideoCaptureResult {
        // Only save to Photos if no custom output directory was specified
        // and directory is PICTURES or DCIM (not DOCUMENTS)
        if (directory == Directory.DOCUMENTS) return result

        val filePath = result.filePath
        var savedAssetId: String? = null
        var saveError: String? = null

        val semaphore = platform.darwin.dispatch_semaphore_create(0)

        PHPhotoLibrary.sharedPhotoLibrary().performChanges(
            changeBlock = {
                val creationRequest = PHAssetChangeRequest.creationRequestForAssetFromVideoAtFileURL(
                    NSURL.fileURLWithPath(filePath),
                )
                savedAssetId = creationRequest?.placeholderForCreatedAsset?.localIdentifier
            },
            completionHandler = { success, error ->
                if (!success || error != null) {
                    saveError = error?.localizedDescription ?: "Failed to save video to Photos"
                    CameraKLogger.e(
                        "CameraK",
                        "CameraK: Failed to save video to Photos: $saveError",
                    )
                }
                platform.darwin.dispatch_semaphore_signal(semaphore)
            },
        )

        platform.darwin.dispatch_semaphore_wait(semaphore, platform.darwin.DISPATCH_TIME_FOREVER)

        // Clean up temp file after saving to Photos
        if (saveError == null) {
            NSFileManager.defaultManager.removeItemAtPath(filePath, null)
        }

        return if (saveError == null && savedAssetId != null) {
            VideoCaptureResult.Success("ph://$savedAssetId", result.durationMs)
        } else {
            // Still return success with temp path if Photos save failed
            result
        }
    }

    private fun FlashMode.toAVCaptureFlashMode(): AVCaptureFlashMode = when (this) {
        FlashMode.ON -> AVCaptureFlashModeOn
        FlashMode.OFF -> AVCaptureFlashModeOff
        FlashMode.AUTO -> AVCaptureFlashModeAuto
    }

    private fun TorchMode.toAVCaptureTorchMode(): AVCaptureTorchMode = when (this) {
        TorchMode.ON -> AVCaptureTorchModeOn
        TorchMode.OFF -> AVCaptureTorchModeOff
        TorchMode.AUTO -> AVCaptureTorchModeAuto
    }

    // ═══════════════════════════════════════════════════════════════
    // Device Orientation
    // ═══════════════════════════════════════════════════════════════

    @Volatile
    private var currentDeviceOrientation = DeviceOrientation.PORTRAIT

    @Volatile
    private var orientationChangedCallback: ((DeviceOrientation) -> Unit)? = null
    private var orientationObserver: Any? = null

    @Volatile
    private var targetOrientation: DeviceOrientation? = null

    actual fun getDeviceOrientation(): DeviceOrientation = currentDeviceOrientation

    actual fun setOnOrientationChangedListener(callback: ((DeviceOrientation) -> Unit)?) {
        removeOrientationObserver()
        orientationChangedCallback = callback
        if (callback != null) {
            UIDevice.currentDevice.beginGeneratingDeviceOrientationNotifications()
            orientationObserver = NSNotificationCenter.defaultCenter.addObserverForName(
                UIDeviceOrientationDidChangeNotification,
                null,
                null,
            ) { _ ->
                val uiOrientation = UIDevice.currentDevice.orientation
                val newOrientation = when (uiOrientation) {
                    UIDeviceOrientation.UIDeviceOrientationPortrait -> DeviceOrientation.PORTRAIT
                    UIDeviceOrientation.UIDeviceOrientationLandscapeLeft -> DeviceOrientation.LANDSCAPE_LEFT
                    UIDeviceOrientation.UIDeviceOrientationPortraitUpsideDown -> DeviceOrientation.PORTRAIT_UPSIDE_DOWN
                    UIDeviceOrientation.UIDeviceOrientationLandscapeRight -> DeviceOrientation.LANDSCAPE_RIGHT
                    else -> null
                }
                if (newOrientation != null && newOrientation != currentDeviceOrientation) {
                    currentDeviceOrientation = newOrientation
                    orientationChangedCallback?.invoke(newOrientation)
                }
            }
        }
    }

    actual fun setTargetOrientation(orientation: DeviceOrientation?) {
        targetOrientation = orientation
        val effective = orientation ?: currentDeviceOrientation
        val avOrientation = effective.toAVCaptureVideoOrientation()
        getCameraPreviewLayer()?.connection?.let { connection ->
            if (connection.isVideoOrientationSupported()) {
                connection.videoOrientation = avOrientation
            }
        }
    }

    private fun removeOrientationObserver() {
        orientationObserver?.let { observer ->
            NSNotificationCenter.defaultCenter.removeObserver(observer)
            UIDevice.currentDevice.endGeneratingDeviceOrientationNotifications()
        }
        orientationObserver = null
    }

    internal fun effectiveVideoOrientation(): AVCaptureVideoOrientation {
        val locked = targetOrientation
        return if (locked != null) {
            locked.toAVCaptureVideoOrientation()
        } else {
            currentVideoOrientation()
        }
    }

    private fun DeviceOrientation.toAVCaptureVideoOrientation(): AVCaptureVideoOrientation = when (this) {
        DeviceOrientation.PORTRAIT -> AVCaptureVideoOrientationPortrait
        DeviceOrientation.LANDSCAPE_LEFT -> AVCaptureVideoOrientationLandscapeRight
        DeviceOrientation.PORTRAIT_UPSIDE_DOWN -> AVCaptureVideoOrientationPortraitUpsideDown
        DeviceOrientation.LANDSCAPE_RIGHT -> AVCaptureVideoOrientationLandscapeLeft
    }
}

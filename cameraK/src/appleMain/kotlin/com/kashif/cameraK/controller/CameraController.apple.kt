package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.CameraCapabilities
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
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.autoreleasepool
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureFlashMode
import platform.AVFoundation.AVCaptureFlashModeAuto
import platform.AVFoundation.AVCaptureFlashModeOff
import platform.AVFoundation.AVCaptureFlashModeOn
import platform.AVFoundation.AVCaptureInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureMovieFileOutput
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureTorchMode
import platform.AVFoundation.AVCaptureTorchModeAuto
import platform.AVFoundation.AVCaptureTorchModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
import platform.AVFoundation.AVCaptureVideoOrientation
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVMediaTypeAudio
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.timeIntervalSince1970
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientationDidChangeNotification
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIViewController
import platform.darwin.DISPATCH_QUEUE_PRIORITY_HIGH
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume

actual class CameraController internal constructor(
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
    customController: CustomCameraController?,
) : UIViewController(null, null) {
    constructor(
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
        flashMode,
        torchMode,
        cameraLens,
        imageFormat,
        qualityPriority,
        directory,
        cameraDeviceType,
        aspectRatio,
        targetResolution,
        mirrorFrontCamera,
        customController = null,
    )

    /** Test seam: the internal constructor can hand in a controller built on a fake session and camera. */
    private val customCameraController = customController ?: CustomCameraController(
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
    // Set on the session queue when the output joins the session; read from recording calls.
    @Volatile
    private var movieFileOutput: AVCaptureMovieFileOutput? = null
    private var videoRecordingDelegate: VideoRecordingDelegate? = null
    private var videoOutputFilePath: String? = null

    init {
        // The configured modes, not CustomCameraController's defaults, are what captures and the
        // torch use; the torch is applied once the session runs and again after each lens switch.
        customCameraController.setFlashMode(flashMode.toAVCaptureFlashMode())
        customCameraController.setTorchMode(torchMode.toAVCaptureTorchMode())
        customCameraController.recordingInProgress = { movieFileOutput?.isRecording() == true }
        customCameraController.captureOrientation = ::effectiveVideoOrientation
        // The metadata output joins the session only while a scanner is set, and the movie output only
        // for a recording: each costs memory and power and limits the formats the session can use.
    }

    private val memoryManager = MemoryManager
    private val pendingCaptures = atomic(0)

    /** Captures AVFoundation may work on at once, as on Android; each holds a slot until its last callback. */
    private val maxConcurrentCaptures = 3

    /** Captures started and not yet finished by AVFoundation, for tests. */
    internal val capturesInFlight: Int get() = pendingCaptures.value

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
    fun isSessionReady(): Boolean = customCameraController.hasSession

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
        lastVideoOrientation = UIDevice.currentDevice.orientation.toVideoOrientation() ?: lastVideoOrientation
        return lastVideoOrientation
    }

    private fun setupCamera() {
        customCameraController.onSessionReady = {
            try {
                customCameraController.setupPreviewLayer(view)
                applyPreviewOrientation()
            } catch (e: Exception) {
                CameraKLogger.e("CameraK", "CameraK Error: setupPreviewLayer", e)
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
                    // An array of the photo's exact size that only the listeners hold: a pooled buffer
                    // is as large as the biggest earlier photo and goes back to the pool while they read.
                    val data = imageData.toByteArray()

                    dispatch_async(dispatch_get_main_queue()) {
                        for (listener in imageCaptureListeners.toList()) {
                            try {
                                listener(data)
                            } catch (e: Exception) {
                                CameraKLogger.e("CameraK", "CameraK: an image capture listener failed", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    CameraKLogger.e("CameraK", "CameraK: copying the captured photo for listeners failed", e)
                }
            }
        }
    }

    /**
     * Sets the scanner's delegate and attaches the metadata output, as a queued change, so a change
     * the scanner queues next (setting its object types) finds the output attached.
     */
    fun setMetadataObjectsDelegate(delegate: AVCaptureMetadataOutputObjectsDelegateProtocol) {
        metadataObjectsDelegate = delegate
        metadataOutput.setMetadataObjectsDelegate(delegate, dispatch_get_main_queue())
        customCameraController.queueConfigurationChange {
            if (!customCameraController.containsOutput(metadataOutput)) {
                customCameraController.safeAddOutput(metadataOutput)
            }
        }
    }

    /**
     * Clears the metadata delegate so QR/barcode callbacks stop. Used by the QR plugin on detach.
     */
    fun clearMetadataObjectsDelegate() {
        metadataObjectsDelegate = null
        metadataOutput.setMetadataObjectsDelegate(null, dispatch_get_main_queue())
        customCameraController.safeRemoveOutput(metadataOutput)
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
        // A rotation of the interface lays the view out again, so this is where the preview follows it.
        applyPreviewOrientation()
    }

    /**
     * Fast file-based capture for iOS - saves directly without ByteArray processing.
     *
     * Each call is its own request: its photo and outcome come back through its own delegate, never
     * a shared slot, so session errors and other captures cannot decide it.
     */
    actual suspend fun takePictureToFile(): ImageCaptureResult = suspendCancellableCoroutine { continuation ->
        if (pendingCaptures.incrementAndGet() > maxConcurrentCaptures) {
            pendingCaptures.decrementAndGet()
            CameraKLogger.e("CameraK", "CameraK: Burst queue full, dropping frame")
            continuation.resume(ImageCaptureResult.Error(Exception("Burst queue full, capture rejected")))
            return@suspendCancellableCoroutine
        }

        val request = PhotoRequest(continuation)
        // Cancelling only detaches the caller: AVFoundation keeps working on the request, so the slot
        // stays taken until its last callback releases it, exactly once.
        continuation.invokeOnCancellation { request.detach() }
        if (!customCameraController.capturePhoto(request)) request.notIssued()
    }

    /** One takePictureToFile call: AVFoundation's callbacks for its capture arrive here and only here. */
    private inner class PhotoRequest(private val continuation: CancellableContinuation<ImageCaptureResult>) :
        PhotoCaptureCallbacks {
        private val settled = atomic(false)
        private val released = atomic(false)
        private val detached = atomic(false)

        fun detach() {
            detached.value = true
        }

        override fun photoProcessed(data: NSData?, error: NSError?) {
            if (!settled.compareAndSet(expect = false, update = true)) return
            if (detached.value) {
                CameraKLogger.d("CameraK", "CameraK: discarding the photo of a cancelled capture")
                return
            }
            if (error != null || data == null) {
                continuation.resume(ImageCaptureResult.Error(photoCaptureFailure(error)))
                return
            }
            // Writing and the Photos import stay off AVFoundation's delegate queue.
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH.toLong(), 0u)) {
                val result = autoreleasepool { savePhoto(data) }
                continuation.resume(result)
            }
        }

        override fun captureFinished(error: NSError?) {
            if (settled.compareAndSet(expect = false, update = true) && !detached.value) {
                continuation.resume(ImageCaptureResult.Error(photoCaptureFailure(error)))
            }
            release()
        }

        /** The camera was not ready, so AVFoundation never got the request. */
        fun notIssued() {
            if (settled.compareAndSet(expect = false, update = true)) {
                continuation.resume(ImageCaptureResult.Error(Exception("Camera not ready for capture")))
            }
            release()
        }

        private fun release() {
            if (released.compareAndSet(expect = false, update = true)) pendingCaptures.decrementAndGet()
        }
    }

    private fun photoCaptureFailure(error: NSError?): Exception = Exception(
        "Photo capture failed: ${error?.let { "${it.domain} ${it.code}: ${it.localizedDescription}" } ?: "no photo"}",
    )

    /**
     * Writes [image] where [directory] says and tells image-capture listeners about the bytes written.
     * Runs off the main thread and off AVFoundation's delegate queue.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun savePhoto(image: NSData): ImageCaptureResult = try {
        val filePath = createTempFile()
        val written = if (imageFormat == ImageFormat.JPEG) {
            image
        } else {
            UIImagePNGRepresentation(image.toUIImage().fixOrientation())
        }

        if (written != null && writeDataToFile(written, filePath)) {
            processImageCapture(written)
            when (directory) {
                Directory.PICTURES, Directory.DCIM -> {
                    val photosPath = saveToPhotosLibrary(filePath, image)
                    NSFileManager.defaultManager.removeItemAtPath(filePath, null)

                    if (photosPath != null) {
                        ImageCaptureResult.SuccessWithFile(photosPath)
                    } else {
                        ImageCaptureResult.Error(Exception("Failed to save to Photos library"))
                    }
                }
                Directory.DOCUMENTS -> ImageCaptureResult.SuccessWithFile(filePath)
            }
        } else {
            ImageCaptureResult.Error(Exception("Failed to write image to file"))
        }
    } catch (e: Exception) {
        CameraKLogger.e("CameraK", "CameraK: saving the captured photo failed", e)
        ImageCaptureResult.Error(e)
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

    actual fun setZoom(zoomRatio: Float) {
        customCameraController.setZoom(zoomRatio)
    }

    actual fun getZoom(): Float = customCameraController.getZoom()

    actual fun getMaxZoom(): Float = customCameraController.getMaxZoom()

    actual fun toggleCameraLens() {
        if (customCameraController.recordingInProgress()) {
            CameraKLogger.w("CameraK", "CameraK: cannot switch the lens while recording; stop the recording first")
            return
        }
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
        if (customCameraController.recordingInProgress()) {
            CameraKLogger.w("CameraK", "CameraK: cannot change the camera device type while recording")
            return
        }
        cameraDeviceType = deviceType
        customCameraController.switchToDeviceType(deviceType)
    }

    actual fun getCameraCapabilities(): CameraCapabilities =
        CameraCapabilities(customCameraController.getCameraCapabilities())

    actual fun startSession() {
        // Note: The actual session start happens in onSessionReady callback (setupCamera).
        // This method is called from CameraKStateHolder.initialize() which may be before
        // viewDidLoad() triggers setupCamera(). The session will start automatically
        // when ready, so we only need to handle the case where session IS ready.
        if (customCameraController.hasSession) {
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

    /**
     * Attaches a movie file output for a recording, as a queued change, and returns it once the
     * change has run; null when there is no session or the session refuses the output.
     */
    internal suspend fun attachMovieOutput(): AVCaptureMovieFileOutput? {
        movieFileOutput?.let { return it }
        if (!customCameraController.hasSession) return null
        val output = AVCaptureMovieFileOutput()
        customCameraController.safeAddOutput(output)
        return suspendCancellableCoroutine { cont ->
            customCameraController.afterQueuedSessionWork {
                val attached = customCameraController.containsOutput(output)
                if (attached) movieFileOutput = output
                cont.resume(output.takeIf { attached })
            }
        }
    }

    /**
     * Detaches a finished recording's movie output and the microphone it added ([isAudioInput]
     * picks it), so the session and the microphone indicator return to their photo-only state.
     */
    internal fun releaseRecordingResources(
        output: AVCaptureMovieFileOutput,
        isAudioInput: (AVCaptureInput) -> Boolean = ::isAudioDeviceInput,
    ) {
        if (movieFileOutput === output) movieFileOutput = null
        customCameraController.safeRemoveOutput(output)
        customCameraController.removeInputsMatching(isAudioInput)
    }

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun startRecording(configuration: VideoConfiguration): String {
        val output = attachMovieOutput() ?: throw IllegalStateException("Movie file output not available")
        return startRecordingOn(output, configuration)
    }

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun startRecordingOn(output: AVCaptureMovieFileOutput, configuration: VideoConfiguration): String =
        suspendCancellableCoroutine { cont ->

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
            delegate.onRecordingEnded = { releaseRecordingResources(output) }
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
        customCameraController.addInputUnlessPresent(
            isPresent = ::isAudioDeviceInput,
            create = {
                platform.AVFoundation.AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeAudio)
                    ?.let { AVCaptureDeviceInput.deviceInputWithDevice(it, null) }
            },
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun isAudioDeviceInput(input: AVCaptureInput): Boolean =
        (input as? AVCaptureDeviceInput)?.device?.hasMediaType(AVMediaTypeAudio) == true

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
        // Unique per capture: up to three are saved at once, on different threads.
        val fileName = "IMG_${NSUUID().UUIDString}.${imageFormat.extension}"

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
                val newOrientation = UIDevice.currentDevice.orientation.toDeviceOrientation()
                if (newOrientation != null && newOrientation != currentDeviceOrientation) {
                    currentDeviceOrientation = newOrientation
                    orientationChangedCallback?.invoke(newOrientation)
                }
            }
        }
    }

    actual fun setTargetOrientation(orientation: DeviceOrientation?) {
        // The target decides how photos and recordings are oriented. The preview shows what the
        // interface shows, so it keeps following the interface orientation.
        targetOrientation = orientation
    }

    /**
     * Turns the preview to the interface's orientation, so a portrait-locked app keeps an upright
     * portrait preview whichever way the device is held. Main thread only.
     */
    private fun applyPreviewOrientation() {
        interfaceOrientation()?.let(customCameraController::orientPreview)
    }

    /**
     * The interface's current orientation as a video orientation, or null when the view has no window
     * scene yet. Test seam: tests answer for a window the simulator does not give them.
     */
    internal var interfaceOrientation: () -> AVCaptureVideoOrientation? = {
        view.window?.windowScene?.interfaceOrientation?.toVideoOrientation()
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
        return locked?.toVideoOrientation() ?: currentVideoOrientation()
    }
}

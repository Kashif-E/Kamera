@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capture.heicCaptureUnsupported
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
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
import com.kashif.cameraK.utils.toByteArray
import com.kashif.cameraK.video.VideoCaptureResult
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureMovieFileOutput
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureVideoOrientation
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVMediaTypeAudio
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.kCMTimeInvalid
import platform.Foundation.NSData
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.UIKit.UIInterfaceOrientation
import platform.darwin.DISPATCH_QUEUE_PRIORITY_HIGH
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume

/**
 * The 1.x iOS camera, kept until 2.0's C1' as a facade over an [AppleCameraEngine]. It is a plain
 * class, not a UIViewController: the engine owns the session, [startSession] starts it with no view,
 * and the preview is a layer camerak's CameraPreviewView hosts ([createPreviewLayer]).
 *
 * Built through a public constructor or the builder, it builds an engine of its own; the bridge's
 * engine builds one over itself. Members that open, stop or switch the camera go through the engine,
 * and so do the flash, torch and zoom setters while the camera is Open; before that the facade keeps
 * them and its startSession passes them to the engine's start. The target and device orientation are
 * the engine's. Captures, recordings and analyzers keep their 1.x bodies on the session the engine
 * owns, until I3' and I4' give the engine those paths.
 */
actual class CameraController private constructor(
    flashMode: FlashMode,
    torchMode: TorchMode,
    @Volatile internal var cameraLens: CameraLens,
    internal var imageFormat: ImageFormat,
    internal var qualityPriority: QualityPrioritization,
    internal var directory: Directory,
    @Volatile internal var cameraDeviceType: CameraDeviceType,
    internal var aspectRatio: AspectRatio,
    internal var targetResolution: Pair<Int, Int>?,
    internal var mirrorFrontCamera: Boolean,
    customController: CustomCameraController?,
    engine: AppleCameraEngine?,
) {
    /** Test seam: a controller built on a fake session and camera, with an engine of its own over it. */
    internal constructor(
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
        customController: CustomCameraController?,
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
        customController,
        engine = null,
    )

    /** The facade [engine] carries to Ready.controller, configured as the engine is. */
    internal constructor(engine: AppleCameraEngine, config: CameraConfiguration) : this(
        flashMode = config.flashMode,
        torchMode = config.torchMode,
        cameraLens = config.cameraLens,
        imageFormat = config.imageFormat,
        qualityPriority = config.qualityPrioritization,
        directory = config.directory,
        cameraDeviceType = config.cameraDeviceType,
        aspectRatio = config.aspectRatio,
        targetResolution = config.targetResolution,
        mirrorFrontCamera = config.mirrorFrontCamera,
        customController = null,
        engine = engine,
    )

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
        engine = null,
    )

    /** The engine this facade runs on: its own, unless the engine built the facade over itself. */
    internal val engine: AppleCameraEngine = engine ?: AppleCameraEngine(
        config = CameraConfiguration(
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
        ),
        environment = CameraKEnvironment(),
        custom = customController ?: CustomCameraController(
            qualityPrioritization = qualityPriority,
            initialCameraLens = cameraLens,
            aspectRatio = aspectRatio,
            targetResolution = targetResolution,
            mirrorFrontCamera = mirrorFrontCamera,
        ),
    ).also { it.adopt(this) }

    private val customCameraController: CustomCameraController get() = engine.custom

    /**
     * The flash, torch and zoom this facade wants, kept in any state and passed to the engine's start;
     * its selection and target orientation are not read (the lens fields and the engine hold those).
     */
    private val desired = atomic(
        DesiredHardware(
            selection = CameraSelection.ByLens(cameraLens, cameraDeviceType),
            flashMode = flashMode,
            torchMode = torchMode,
            zoomRatio = 1f,
            targetOrientation = null,
        ),
    )

    private fun updateDesired(flashMode: FlashMode? = null, torchMode: TorchMode? = null, zoomRatio: Float? = null) =
        desired.update { current ->
            DesiredHardware(
                selection = current.selection,
                flashMode = flashMode ?: current.flashMode,
                torchMode = torchMode ?: current.torchMode,
                zoomRatio = zoomRatio ?: current.zoomRatio,
                targetOrientation = null,
            )
        }
    private var imageCaptureListeners = mutableListOf<(ByteArray) -> Unit>()
    private var metadataOutput = AVCaptureMetadataOutput()
    private var metadataObjectsDelegate: AVCaptureMetadataOutputObjectsDelegateProtocol? = null

    /** Scanner callbacks run here, one at a time and off main, so scanning never competes with the UI. */
    private val metadataQueue = dispatch_queue_create("com.kashif.cameraK.metadata", null)

    // Video recording
    // Set on the session queue when the output joins the session; read from recording calls.
    @Volatile
    private var movieFileOutput: AVCaptureMovieFileOutput? = null

    /** Free space a recording leaves on disk: room for the system and the file's finalisation. */
    private val minFreeRecordingDiskBytes = 200L * 1024 * 1024

    /** Test seam: the movie-file-output calls a recording makes. */
    internal var movieRecorder: MovieRecorder = AVMovieRecorder

    /** Test seam: the microphone's session input for a recording with audio, or why there is none. */
    internal var microphoneInput: () -> Result<AVCaptureInput> = ::defaultMicrophoneInput

    /** How long startRecording waits for AVFoundation to report the recording started. */
    internal var recordingStartTimeoutMs: Long = 10_000

    /** How long stopRecording waits for AVFoundation to finish the file. */
    internal var recordingFinishTimeoutMs: Long = 10_000

    /** Whether the movie output can pause; AVCaptureFileOutput's pause exists from iOS 18. */
    internal var pauseSupported: () -> Boolean = { isOsAtLeast(18) }

    init {
        // The configured flash, not CustomCameraController's default, is what captures use until the
        // engine's start applies the desired hardware.
        customCameraController.setFlashMode(flashMode.toAVCaptureFlashMode())
        customCameraController.recordingInProgress = { movieFileOutput?.let(movieRecorder::isRecording) == true }
        customCameraController.onPhotoCapture = { image -> image?.let(::processImageCapture) }
        customCameraController.onError = { error ->
            CameraKLogger.e("CameraK", "CameraK: the camera reported an error", error)
        }
        // The metadata output joins the session only while a scanner is set, and the movie output only
        // for a recording: each costs memory and power and limits the formats the session can use.
    }

    private val pendingCaptures = atomic(0)

    /** Captures AVFoundation may work on at once, as on Android; each holds a slot until its last callback. */
    private val maxConcurrentCaptures = 3

    /** Captures started and not yet finished by AVFoundation, for tests. */
    internal val capturesInFlight: Int get() = pendingCaptures.value

    fun getCameraPreviewLayer() = customCameraController.cameraPreviewLayer

    /**
     * A preview layer bound to the engine's session, mirrored for the front camera; camerak's
     * CameraPreviewView hosts it. Main thread only.
     */
    @InternalCameraKApi
    fun createPreviewLayer(): AVCaptureVideoPreviewLayer = engine.preview.createPreviewLayer()

    /**
     * The view hosting the preview laid out again, under an interface in [orientation] (null when it
     * has no window scene yet): the preview turns to the interface. Main thread only.
     */
    @InternalCameraKApi
    fun previewLayoutChanged(orientation: UIInterfaceOrientation?) {
        hostInterfaceOrientation = orientation?.toVideoOrientation()
        applyPreviewOrientation()
    }

    /** The interface orientation the preview's host last reported. Main thread only. */
    private var hostInterfaceOrientation: AVCaptureVideoOrientation? = null

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

    private fun processImageCapture(imageData: NSData) {
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH.toLong(), 0u)) {
            autoreleasepool {
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
     * the scanner queues next (setting its object types) finds the output attached. The delegate is
     * called on a serial background queue, not on main; hop to main before touching UI state.
     */
    fun setMetadataObjectsDelegate(delegate: AVCaptureMetadataOutputObjectsDelegateProtocol) {
        metadataObjectsDelegate = delegate
        metadataOutput.setMetadataObjectsDelegate(delegate, metadataQueue)
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
        metadataOutput.setMetadataObjectsDelegate(null, metadataQueue)
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

    /**
     * Fast file-based capture for iOS - saves directly without ByteArray processing.
     *
     * Each call is its own request: its photo and outcome come back through its own delegate, never
     * a shared slot, so session errors and other captures cannot decide it.
     */
    actual suspend fun takePictureToFile(): ImageCaptureResult {
        if (imageFormat == ImageFormat.HEIC) return heicCaptureUnsupported("CameraK")
        val photo = suspendCancellableCoroutine { continuation ->
            if (pendingCaptures.incrementAndGet() > maxConcurrentCaptures) {
                pendingCaptures.decrementAndGet()
                CameraKLogger.e("CameraK", "CameraK: Burst queue full, dropping frame")
                continuation.resume(Result.failure(Exception("Burst queue full, capture rejected")))
                return@suspendCancellableCoroutine
            }

            val request = PhotoRequest(continuation)
            // Cancelling only detaches the caller: AVFoundation keeps working on the request, so the slot
            // stays taken until its last callback releases it, exactly once.
            continuation.invokeOnCancellation { request.detach() }
            if (!customCameraController.capturePhoto(request)) request.notIssued()
        }
        val data = photo.getOrElse { return ImageCaptureResult.Error(it as Exception) }
        // Writing and the Photos import stay off the caller's thread and AVFoundation's delegate queue.
        return withContext(Dispatchers.IO) { savePhoto(data) }
    }

    /**
     * One takePictureToFile call: AVFoundation's callbacks for its capture arrive here and only here,
     * and its continuation gets the photo's data or why there is none.
     */
    private inner class PhotoRequest(private val continuation: CancellableContinuation<Result<NSData>>) :
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
                continuation.resume(Result.failure(photoCaptureFailure(error)))
                return
            }
            continuation.resume(Result.success(data))
        }

        override fun captureFinished(error: NSError?) {
            if (settled.compareAndSet(expect = false, update = true) && !detached.value) {
                continuation.resume(Result.failure(photoCaptureFailure(error)))
            }
            release()
        }

        /** The camera was not ready, so AVFoundation never got the request. */
        fun notIssued() {
            if (settled.compareAndSet(expect = false, update = true)) {
                continuation.resume(Result.failure(Exception("Camera not ready for capture")))
            }
            release()
        }

        private fun release() {
            if (released.compareAndSet(expect = false, update = true)) pendingCaptures.decrementAndGet()
        }
    }

    private fun photoCaptureFailure(error: NSError?): Exception =
        Exception("Photo capture failed: ${error?.summary ?: "no photo"}", error?.let(::NSErrorException))

    /**
     * Writes [image] where [directory] says, tells image-capture listeners about the bytes written,
     * and for PICTURES and DCIM imports the file into Photos. Runs on [Dispatchers.IO].
     */
    @OptIn(ExperimentalForeignApi::class)
    private suspend fun savePhoto(image: NSData): ImageCaptureResult {
        val filePath = try {
            autoreleasepool { writePhoto(image) }
        } catch (e: Exception) {
            CameraKLogger.e("CameraK", "CameraK: saving the captured photo failed", e)
            return ImageCaptureResult.Error(e)
        } ?: return ImageCaptureResult.Error(Exception("Failed to write image to file"))

        return when (directory) {
            Directory.PICTURES, Directory.DCIM -> importIntoPhotos { completion ->
                photosLibrary.importImage(NSURL.fileURLWithPath(filePath), completion)
            }.fold(
                onSuccess = { assetId ->
                    removeImportedFile(filePath)
                    ImageCaptureResult.SuccessWithFile("ph://$assetId")
                },
                // The file is the only copy of the photo, so it stays where it was written.
                onFailure = { ImageCaptureResult.Error(it as Exception) },
            )
            Directory.DOCUMENTS -> ImageCaptureResult.SuccessWithFile(filePath)
        }
    }

    /** Writes [image] in [imageFormat] to a new file and hands the bytes to listeners; its path, or null. */
    @OptIn(ExperimentalForeignApi::class)
    private fun writePhoto(image: NSData): String? {
        val filePath = createTempFile()
        if (imageFormat == ImageFormat.JPEG) {
            writeDataToFile(image, filePath).getOrThrow()
            processImageCapture(image)
        } else {
            if (!writePng(image, filePath)) {
                CameraKLogger.e("CameraK", "CameraK: ImageIO could not write the photo as PNG")
                return null
            }
            // Listeners take the PNG's bytes, read back once ImageIO has released the decoded photo.
            NSData.dataWithContentsOfFile(filePath)?.let(::processImageCapture)
                ?: CameraKLogger.e("CameraK", "CameraK: reading the PNG back for image-capture listeners failed")
        }
        return filePath
    }

    actual fun toggleFlashMode() {
        setFlashMode(
            when (desired.value.flashMode) {
                FlashMode.OFF -> FlashMode.ON
                FlashMode.ON -> FlashMode.AUTO
                FlashMode.AUTO -> FlashMode.OFF
            },
        )
    }

    /**
     * Kept for the next start in any state; while Open, set through the engine. Before Open the mode is
     * also stored as the capture parameter, as 1.x did, which the engine's start sets again. A mode the
     * Open camera does not support is kept as OFF, as 1.x did.
     */
    actual fun setFlashMode(mode: FlashMode) {
        val kept = engine.facadeFlashMode(mode)
        updateDesired(flashMode = kept)
        engine.launchFacadeCommand(
            "setting the flash mode",
            whenNotOpen = { customCameraController.setFlashMode(kept.toAVCaptureFlashMode()) },
        ) { setFlashMode(kept) }
    }

    actual fun getFlashMode(): FlashMode? = desired.value.flashMode

    actual fun toggleTorchMode() {
        setTorchMode(
            when (desired.value.torchMode) {
                TorchMode.OFF -> TorchMode.ON
                TorchMode.ON -> TorchMode.AUTO
                TorchMode.AUTO -> TorchMode.OFF
            },
        )
    }

    /** Kept for the next start in any state; while Open, set through the engine. */
    actual fun setTorchMode(mode: TorchMode) {
        updateDesired(torchMode = mode)
        engine.launchFacadeCommand("setting the torch") { setTorchMode(mode) }
    }

    actual fun getTorchMode(): TorchMode? = desired.value.torchMode

    /**
     * Kept for the next start in any state, clamped to the camera in use as 1.x did; while Open, set
     * through the engine, which clamps it on the session queue.
     */
    actual fun setZoom(zoomRatio: Float) {
        val camera = customCameraController.currentDevice
        val kept = if (camera != null && camera.maxAvailableVideoZoomFactor > camera.minVideoZoomFactor) {
            zoomRatio.coerceIn(camera.minVideoZoomFactor.toFloat(), camera.maxAvailableVideoZoomFactor.toFloat())
        } else {
            zoomRatio
        }
        updateDesired(zoomRatio = kept)
        engine.launchFacadeCommand("setting the zoom") { setZoom(zoomRatio) }
    }

    actual fun getZoom(): Float = desired.value.zoomRatio

    actual fun getMaxZoom(): Float = customCameraController.getMaxZoom()

    actual fun toggleCameraLens() {
        if (customCameraController.recordingInProgress()) {
            CameraKLogger.w("CameraK", "CameraK: cannot switch the lens while recording; stop the recording first")
            return
        }
        val previous = cameraLens
        val next = if (previous == CameraLens.BACK) CameraLens.FRONT else CameraLens.BACK
        cameraLens = next
        // Not started yet: the next startSession opens the new lens. A refused switch keeps the old one.
        switchThroughEngine(CameraSelection.ByLens(next, cameraDeviceType)) {
            if (cameraLens == next) cameraLens = previous
        }
    }

    /** Switches through the engine; [revert] undoes the facade's field when the switch was refused. */
    private fun switchThroughEngine(selection: CameraSelection, revert: () -> Unit) {
        engine.switchCamera(selection) { result ->
            val error = (result as? CameraKResult.Failure)?.error
            if (error != null && error != CameraKError.NotReady) {
                CameraKLogger.e("CameraK", "CameraK: switching the camera failed: $error", error.cause)
                revert()
            }
        }
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
        val previous = cameraDeviceType
        cameraDeviceType = deviceType
        // 1.x left the camera as it was for a type iOS has no discovery type for (MACRO).
        if (deviceType == CameraDeviceType.MACRO) return
        switchThroughEngine(CameraSelection.ByLens(cameraLens, deviceType)) {
            if (cameraDeviceType == deviceType) cameraDeviceType = previous
        }
    }

    actual fun getCameraCapabilities(): CameraCapabilities =
        CameraCapabilities(customCameraController.getCameraCapabilities())

    /** Starts this facade's engine on its lens and desired hardware, and the engine's target; no view is needed. */
    actual fun startSession() {
        val wanted = desired.value
        engine.launchStart(
            DesiredHardware(
                selection = CameraSelection.ByLens(cameraLens, cameraDeviceType),
                flashMode = wanted.flashMode,
                torchMode = wanted.torchMode,
                zoomRatio = wanted.zoomRatio,
                targetOrientation = engine.targetOrientation,
            ),
        )
    }

    actual fun stopSession() {
        engine.launchStop()
    }

    actual fun addImageCaptureListener(listener: (ByteArray) -> Unit) {
        imageCaptureListeners.add(listener)
    }

    actual fun removeImageCaptureListener(listener: (ByteArray) -> Unit) {
        imageCaptureListeners.remove(listener)
    }

    /** Closes this facade's engine, which tears the session down once however often this runs. */
    actual fun cleanup() {
        engine.close()
    }

    private val released = atomic(false)

    /**
     * What the facade holds beyond the engine: a running recording, stopped so its file is finished.
     * The engine calls it once, when it closes.
     */
    internal fun releaseForEngineClose() {
        if (!released.compareAndSet(expect = false, update = true)) return
        // A stopRecording already waiting still gets the result, since the recording's delegate outlives this.
        activeRecording?.let { recording ->
            recording.stopRequested.value = true
            if (movieRecorder.isRecording(recording.output)) movieRecorder.stop(recording.output)
        }
        movieFileOutput = null
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

    /** A recording from startRecording until its file is finished. */
    private class ActiveRecording(val output: AVCaptureMovieFileOutput, val delegate: VideoRecordingDelegate) {
        /** Set once stopRecording (or cleanup) asked for the stop, so the result has a taker. */
        val stopRequested = atomic(false)
    }

    @Volatile
    private var activeRecording: ActiveRecording? = null

    /** Held from the start of startRecording until it returns, so a second start is refused at once. */
    private val recordingStarting = atomic(false)

    /** The result of a recording that ended by itself (a limit, an interruption), for the next stopRecording. */
    private val unclaimedRecordingResult = atomic<VideoCaptureResult?>(null)

    /**
     * Starts a recording and returns its file path once AVFoundation reports it has started. A start
     * while a recording is active or starting fails with IllegalStateException.
     */
    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun startRecording(configuration: VideoConfiguration): String {
        if (activeRecording != null || !recordingStarting.compareAndSet(expect = false, update = true)) {
            throw IllegalStateException("Recording already in progress")
        }
        try {
            // Before anything is attached, so a directory that cannot be made leaves the session as it was.
            val filePath = createVideoTempFile(configuration)
            val output = attachMovieOutput() ?: throw IllegalStateException("Movie file output not available")
            if (movieRecorder.isRecording(output)) throw IllegalStateException("Recording already in progress")
            unclaimedRecordingResult.getAndSet(null)?.let {
                CameraKLogger.w(
                    "CameraK",
                    "CameraK: a new recording replaces the unclaimed result of one that ended by itself",
                )
            }

            if (configuration.enableAudio) {
                addAudioInputIfNeeded()
            }

            val fileURL = NSURL.fileURLWithPath(filePath)
            // Delete existing file at path if any
            NSFileManager.defaultManager.removeItemAtPath(filePath, null)

            applyRecordingLimits(output, configuration)

            val delegate = VideoRecordingDelegate()
            val recording = ActiveRecording(output, delegate)
            delegate.onRecordingEnded = { result -> recordingEnded(recording, result) }
            activeRecording = recording

            // The effective orientation, so a locked orientation (setTargetOrientation) is honored.
            val orientation = effectiveVideoOrientation()
            dispatch_async(dispatch_get_main_queue()) {
                movieRecorder.start(output, fileURL, orientation, delegate)
            }

            try {
                withTimeout(recordingStartTimeoutMs) { delegate.started.await() }
            } catch (e: TimeoutCancellationException) {
                abandonStart(recording)
                throw IllegalStateException("The recording did not start within $recordingStartTimeoutMs ms", e)
            } catch (e: CancellationException) {
                abandonStart(recording)
                throw e
            }
            return filePath
        } finally {
            recordingStarting.value = false
        }
    }

    /**
     * Keeps [minFreeRecordingDiskBytes] free (AVFoundation ends the recording, still playable, before
     * the disk fills) and stops at [VideoConfiguration.maxDuration] when one is set.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun applyRecordingLimits(output: AVCaptureMovieFileOutput, configuration: VideoConfiguration) {
        output.minFreeDiskSpaceLimit = minFreeRecordingDiskBytes
        val maxDuration = configuration.maxDuration
        output.maxRecordedDuration = if (maxDuration != null && maxDuration.isPositive()) {
            CMTimeMakeWithSeconds(maxDuration.inWholeMilliseconds / 1000.0, 600)
        } else {
            kCMTimeInvalid.readValue()
        }
    }

    /**
     * Stops a recording whose start was given up, so no recording runs without an owner, and frees
     * the controller for the next start even if AVFoundation never reports this one finished.
     */
    private fun abandonStart(recording: ActiveRecording) {
        recording.stopRequested.value = true
        if (activeRecording === recording) activeRecording = null
        dispatch_async(dispatch_get_main_queue()) {
            if (movieRecorder.isRecording(recording.output)) movieRecorder.stop(recording.output)
            releaseRecordingResources(recording.output)
        }
    }

    /**
     * Runs when [recording]'s file is finished, however it ended: detaches its movie output and
     * microphone, and keeps the result of a recording nobody stopped for the next stopRecording.
     */
    private fun recordingEnded(recording: ActiveRecording, result: VideoCaptureResult) {
        releaseRecordingResources(recording.output)
        // Kept before the recording stops being active, so a concurrent stopRecording finds one or the
        // other. A recording that never started belongs to its failed startRecording, not to a stop.
        val started = recording.delegate.started.isCompleted && !recording.delegate.started.isCancelled
        if (started && !recording.stopRequested.value) unclaimedRecordingResult.value = result
        if (activeRecording === recording) activeRecording = null
    }

    /**
     * Stops the recording and returns its result once AVFoundation has finished the file, or an error
     * after [recordingFinishTimeoutMs]. A recording that already ended by itself reports its result.
     */
    actual suspend fun stopRecording(): VideoCaptureResult {
        val recording = activeRecording
        if (recording == null || !recording.stopRequested.compareAndSet(expect = false, update = true)) {
            val unclaimed = unclaimedRecordingResult.getAndSet(null)
                ?: return VideoCaptureResult.Error(Exception("No active recording"))
            return savedToLibrary(unclaimed)
        }

        dispatch_async(dispatch_get_main_queue()) {
            movieRecorder.stop(recording.output)
        }

        val result = withTimeoutOrNull(recordingFinishTimeoutMs) { recording.delegate.finished.await() }
        if (result == null) {
            CameraKLogger.e("CameraK", "CameraK: the recording did not finish within $recordingFinishTimeoutMs ms")
            // Free the controller for the next recording even if AVFoundation never reports this one.
            if (activeRecording === recording) activeRecording = null
            releaseRecordingResources(recording.output)
            return VideoCaptureResult.Error(
                IllegalStateException("The recording did not finish within $recordingFinishTimeoutMs ms"),
            )
        }
        unclaimedRecordingResult.compareAndSet(result, null)
        return savedToLibrary(result)
    }

    /** Imports a successful recording into Photos when [directory] asks for it, off the caller's thread. */
    private suspend fun savedToLibrary(result: VideoCaptureResult): VideoCaptureResult =
        if (result is VideoCaptureResult.Success) {
            withContext(Dispatchers.IO) { saveVideoToPhotosLibrary(result) }
        } else {
            result
        }

    /**
     * Pauses the recording; a no-op when nothing is recording. Below iOS 18 AVCaptureMovieFileOutput
     * cannot pause, so this throws UnsupportedOperationException rather than leave the camera and
     * microphone recording while the app shows "paused". It returns only once the movie output
     * reports the recording paused.
     */
    actual suspend fun pauseRecording() {
        val recording = activeRecording ?: return
        if (!pauseSupported()) {
            throw UnsupportedOperationException("Pausing a recording needs iOS 18 or later")
        }
        movieRecorder.pause(recording.output)
        if (!movieRecorder.isPaused(recording.output)) {
            throw IllegalStateException("The movie output did not pause the recording")
        }
    }

    /**
     * Resumes a paused recording; a no-op when nothing is recording or the recording is not paused,
     * which below iOS 18 it never is.
     */
    actual suspend fun resumeRecording() {
        val recording = activeRecording ?: return
        if (!pauseSupported() || !movieRecorder.isPaused(recording.output)) return
        movieRecorder.resume(recording.output)
        if (movieRecorder.isPaused(recording.output)) {
            throw IllegalStateException("The movie output did not resume the recording")
        }
    }

    /**
     * Adds the microphone for a recording with audio. A microphone that cannot be added (one the user
     * has not authorised) is reported to onError with AVFoundation's error, and the recording goes
     * ahead without audio.
     */
    private fun addAudioInputIfNeeded() {
        customCameraController.addInputUnlessPresent(
            what = "the microphone",
            isPresent = ::isAudioDeviceInput,
            create = microphoneInput,
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
            createDirectoryIfMissing(dir, "the recording's output directory")
            "$dir/$fileName"
        } else if (directory == Directory.DOCUMENTS) {
            // Documents/CameraK, like photos: iOS purges the temporary directory.
            "${cameraKDocumentsDirectory()}/$fileName"
        } else {
            // PICTURES/DCIM: a temporary file until it is imported into Photos.
            val tempDir = platform.Foundation.NSTemporaryDirectory()
            "$tempDir$fileName"
        }
    }

    /** Documents/CameraK, created when missing; the temporary directory when there is no Documents. */
    @OptIn(ExperimentalForeignApi::class)
    private fun cameraKDocumentsDirectory(): String {
        val documentsDir = platform.Foundation.NSSearchPathForDirectoriesInDomains(
            platform.Foundation.NSDocumentDirectory,
            platform.Foundation.NSUserDomainMask,
            true,
        ).firstOrNull() as? String ?: platform.Foundation.NSTemporaryDirectory()

        val cameraKDir = "$documentsDir/CameraK"
        createDirectoryIfMissing(cameraKDir, "Documents/CameraK")
        return cameraKDir
    }

    /**
     * Creates the directory at [path] when there is none; throws, with the file system's NSError as
     * the cause, when it cannot. [what] names the directory in the message, never its path.
     */
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun createDirectoryIfMissing(path: String, what: String) = memScoped {
        val fileManager = NSFileManager.defaultManager
        if (fileManager.fileExistsAtPath(path)) return@memScoped
        val error = alloc<ObjCObjectVar<NSError?>>()
        val created = fileManager.createDirectoryAtPath(
            path,
            withIntermediateDirectories = true,
            attributes = null,
            error = error.ptr,
        )
        if (!created) {
            val cause = error.value
            throw IllegalStateException(
                "Could not create $what${cause?.let { ": ${it.summary}" }.orEmpty()}",
                cause?.let(::NSErrorException),
            )
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
            Directory.DOCUMENTS -> "${cameraKDocumentsDirectory()}/$fileName"
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun saveVideoToPhotosLibrary(result: VideoCaptureResult.Success): VideoCaptureResult {
        // Only save to Photos if no custom output directory was specified
        // and directory is PICTURES or DCIM (not DOCUMENTS)
        if (directory == Directory.DOCUMENTS) return result

        val filePath = result.filePath
        return importIntoPhotos { completion ->
            photosLibrary.importVideo(NSURL.fileURLWithPath(filePath), completion)
        }.fold(
            onSuccess = { assetId ->
                removeImportedFile(filePath)
                VideoCaptureResult.Success("ph://$assetId", result.durationMs)
            },
            // The video stays at its temporary path, so nothing recorded is lost.
            onFailure = { VideoCaptureResult.Error(it as Exception) },
        )
    }

    /** Test seam: the Photos library photos and recordings are imported into. */
    internal var photosLibrary: PhotosLibrary = SystemPhotosLibrary

    /** How long a capture or a recording's stop waits for a Photos import before reporting it failed. */
    internal var photosImportTimeoutMs: Long = 60_000

    /**
     * Runs the Photos import [start] begins and returns the new asset's identifier, or the failure:
     * what Photos reported (its error domain and code), or that it did not finish within
     * [photosImportTimeoutMs]. It suspends rather than holding a thread, so an import Photos never
     * finishes costs nothing but the wait.
     */
    private suspend fun importIntoPhotos(start: (completion: (String?, NSError?) -> Unit) -> Unit): Result<String> {
        val outcome = withTimeoutOrNull(photosImportTimeoutMs) {
            suspendCancellableCoroutine { continuation ->
                start { localIdentifier, error ->
                    if (!continuation.isActive) {
                        CameraKLogger.w("CameraK", "CameraK: Photos finished an import after CameraK stopped waiting")
                    }
                    // Ignored when the wait was cancelled or timed out.
                    continuation.resume(localIdentifier to error)
                }
            }
        }
        val failure = when {
            outcome == null -> Exception("Photos did not finish saving within $photosImportTimeoutMs ms")
            outcome.first == null -> Exception(
                "Saving to the Photos library failed: ${outcome.second?.summary ?: "Photos created no asset"}",
                outcome.second?.let(::NSErrorException),
            )
            else -> return Result.success(outcome.first!!)
        }
        CameraKLogger.e("CameraK", "CameraK: ${failure.message}; the file stays where it was written", failure.cause)
        return Result.failure(failure)
    }

    /** Removes the temporary file of a capture or recording Photos now holds. */
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun removeImportedFile(path: String) = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        if (!NSFileManager.defaultManager.removeItemAtPath(path, error.ptr)) {
            CameraKLogger.w(
                "CameraK",
                "CameraK: removing the temporary copy of an item saved to Photos failed: " +
                    "${error.value?.domain} ${error.value?.code}",
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Device Orientation
    // ═══════════════════════════════════════════════════════════════

    /** How the device is held, as the engine observes it between start and stop. */
    actual fun getDeviceOrientation(): DeviceOrientation = engine.deviceOrientation.value

    /** [callback] hears each orientation the engine observes; null removes it. */
    actual fun setOnOrientationChangedListener(callback: ((DeviceOrientation) -> Unit)?) {
        engine.setDeviceOrientationListener(callback)
    }

    /**
     * Sets the engine's target orientation in any state, as 1.x did. The target decides how photos and
     * recordings are oriented. The preview shows what the interface shows, so it keeps following the
     * interface orientation.
     */
    actual fun setTargetOrientation(orientation: DeviceOrientation?) {
        engine.storeTargetOrientation(orientation)
    }

    /**
     * Turns the preview to the interface's orientation, so a portrait-locked app keeps an upright
     * portrait preview whichever way the device is held. Main thread only.
     */
    private fun applyPreviewOrientation() {
        interfaceOrientation()?.let(customCameraController::orientPreview)
    }

    /**
     * The interface's current orientation as a video orientation, as the preview's host last
     * reported it; null before it has a window scene. Test seam: tests answer for a window the
     * simulator does not give them.
     */
    internal var interfaceOrientation: () -> AVCaptureVideoOrientation? = { hostInterfaceOrientation }

    /** The orientation photos and movies take: the engine's target when set, otherwise the device's. */
    internal fun effectiveVideoOrientation(): AVCaptureVideoOrientation = engine.captureVideoOrientation()
}

/** The default microphone's input, or why there is none: AVFoundation's NSError for one it refuses. */
@OptIn(ExperimentalForeignApi::class)
private fun defaultMicrophoneInput(): Result<AVCaptureInput> = memScoped {
    val microphone = platform.AVFoundation.AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeAudio)
        ?: return@memScoped Result.failure(IllegalStateException("No microphone is available"))
    val error = alloc<ObjCObjectVar<NSError?>>()
    AVCaptureDeviceInput.deviceInputWithDevice(microphone, error.ptr)?.let { Result.success(it) }
        ?: Result.failure(
            error.value?.let(::NSErrorException)
                ?: IllegalStateException("AVFoundation made no input for the microphone and gave no error"),
        )
}

/**
 * Writes [data] to the file at [filePath], atomically; a failure carries the file system's NSError
 * as its cause, and its message names neither the file nor its path.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun writeDataToFile(data: NSData, filePath: String): Result<Unit> = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    if (data.writeToFile(filePath, NSDataWritingAtomic, error.ptr)) {
        Result.success(Unit)
    } else {
        val cause = error.value
        Result.failure(
            IllegalStateException(
                "Could not write the file${cause?.let { ": ${it.summary}" }.orEmpty()}",
                cause?.let(::NSErrorException),
            ),
        )
    }
}

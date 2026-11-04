@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capture.heicCaptureUnsupported
import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoCaptureResult
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.FrameGrabber
import org.bytedeco.javacv.FrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import org.bytedeco.javacv.FrameConverter as JavaCvFrameConverter

/**
 * The desktop camera controller, backed by JavaCV. Until C1' it is a facade over a desktop engine
 * (§12.1): the engine owns the session and its one grab loop, which feeds the preview, captures
 * and recordings; captures are written under the user's home and recordings are H.264 MP4 files
 * without audio. A controller built with a public constructor builds and owns its own engine;
 * `Ready.controller` is the facade the bridge's engine built over itself.
 */
actual class CameraController internal constructor(
    private val imageFormat: ImageFormat,
    private val directory: Directory,
    private val aspectRatio: AspectRatio,
    sessionDispatcher: CoroutineDispatcher,
    private val recorderFactory: (String, VideoConfiguration) -> FrameRecorder,
    // The folder that Directory and the default video folder resolve under: the user's home.
    private val userHome: File,
    private val nanoClock: () -> Long,
    private val recordingConverter: () -> JavaCvFrameConverter<BufferedImage>,
    private val writeCapture: (BufferedImage, ImageFormat, File) -> Unit,
    private val readCapture: (File) -> ByteArray,
    engine: (CameraController) -> DesktopCameraEngine,
) {
    constructor(
        imageFormat: ImageFormat,
        directory: Directory,
        horizontalFlip: Boolean = false,
        customGrabber: FrameGrabber? = null,
        targetResolution: Pair<Int, Int>? = null,
        aspectRatio: AspectRatio = AspectRatio.RATIO_16_9,
    ) : this(
        imageFormat = imageFormat,
        directory = directory,
        aspectRatio = aspectRatio,
        sessionDispatcher = Dispatchers.IO,
        recorderFactory = ::createFrameRecorder,
        userHome = defaultUserHome(),
        nanoClock = System::nanoTime,
        recordingConverter = ::Java2DFrameConverter,
        writeCapture = ::writeImage,
        readCapture = File::readBytes,
        engine = { facade -> ownEngine(facade, customGrabber, targetResolution, horizontalFlip, System::nanoTime) },
    )

    internal constructor(
        imageFormat: ImageFormat,
        directory: Directory,
        customGrabber: FrameGrabber?,
        sessionDispatcher: CoroutineDispatcher,
        recorderFactory: (String, VideoConfiguration) -> FrameRecorder,
        horizontalFlip: Boolean = false,
        userHome: File = defaultUserHome(),
        nanoClock: () -> Long = System::nanoTime,
        recordingConverter: () -> JavaCvFrameConverter<BufferedImage> = ::Java2DFrameConverter,
        writeCapture: (BufferedImage, ImageFormat, File) -> Unit = ::writeImage,
        readCapture: (File) -> ByteArray = File::readBytes,
    ) : this(
        imageFormat = imageFormat,
        directory = directory,
        aspectRatio = AspectRatio.RATIO_16_9,
        sessionDispatcher = sessionDispatcher,
        recorderFactory = recorderFactory,
        userHome = userHome,
        nanoClock = nanoClock,
        recordingConverter = recordingConverter,
        writeCapture = writeCapture,
        readCapture = readCapture,
        engine = { facade -> ownEngine(facade, customGrabber, null, horizontalFlip, nanoClock) },
    )

    /** The facade an engine builds over itself: the bridge's, read through `Ready.controller`. */
    internal constructor(
        engine: DesktopCameraEngine,
        config: CameraConfiguration,
        userHome: File = defaultUserHome(),
    ) : this(
        imageFormat = config.imageFormat,
        directory = config.directory,
        aspectRatio = config.aspectRatio,
        sessionDispatcher = Dispatchers.IO,
        recorderFactory = ::createFrameRecorder,
        userHome = userHome,
        nanoClock = System::nanoTime,
        recordingConverter = ::Java2DFrameConverter,
        writeCapture = ::writeImage,
        readCapture = File::readBytes,
        engine = { engine },
    )

    /** The engine this controller is a facade over (§12.1). */
    internal val engine: DesktopCameraEngine = engine(this)

    // The engine's producer: captures and recordings read copies of its frames.
    private val cameraGrabber: CameraGrabber get() = engine.grabber

    // The controller's own scope: the session calls and the recording run in it, and cleanup() cancels it.
    private val scope = CoroutineScope(
        SupervisorJob() + sessionDispatcher +
            CoroutineExceptionHandler { _, e -> CameraKLogger.e("CameraK", "CameraK: Camera session failed", e) },
    )
    private val closed = atomic(false)

    // The hardware the 1.x setters asked for, kept across stop and start and passed to the
    // engine's start (W4 decision G3).
    private val desired = atomic(FACADE_DESIRED)

    /**
     * The camera's frames, latest first, as opaque `TYPE_3BYTE_BGR` images: the engine's preview
     * frames. Each image comes from a small ring the producer reuses: it is never written while it
     * is the latest frame or the next one is published, but it is overwritten after that. Read it
     * before the next frame and do not keep it; copy it to keep it.
     */
    val frameFlow: Flow<BufferedImage> = this.engine.preview.frames.filterNotNull().map { it.image }
    private val qualityPriority: QualityPrioritization = QualityPrioritization.NONE

    // Video recording

    @Volatile private var isCurrentlyRecording = false
    private val activeRecording = atomic<ActiveRecording?>(null)
    private val recordingLock = Mutex()
    private var recordingOutputPath: String? = null

    // Copy-on-write: listeners change rarely and are iterated once per capture, from the capture's
    // thread, while another thread may add or remove one. Empty means a capture reads nothing back.
    // Delivery: on the Dispatchers.IO thread running takePictureToFile, in order, before it returns.
    private val listeners = CopyOnWriteArrayList<(ByteArray) -> Unit>()

    actual suspend fun takePictureToFile(): ImageCaptureResult {
        if (imageFormat == ImageFormat.HEIC) return heicCaptureUnsupported("CameraK")
        return withContext(Dispatchers.IO) {
            val currentImage = cameraGrabber.awaitSnapshot()

            if (currentImage == null) {
                return@withContext ImageCaptureResult.Error(IllegalStateException("No image available"))
            }

            var outFile: File? = null
            val result = try {
                val ext = if (imageFormat == ImageFormat.PNG) "png" else "jpg"
                val file = createUniqueFile(File(pictureFolder(), "CameraK"), "CameraK", ext).also { outFile = it }
                writeCapture(currentImage, imageFormat, file)
                ImageCaptureResult.SuccessWithFile(file.absolutePath)
            } catch (e: Exception) {
                CameraKLogger.e("CameraK", "CameraK: Error writing the captured image", e)
                outFile?.delete()
                return@withContext ImageCaptureResult.Error(e)
            }
            // The photo is written; nothing a listener does can undo that or change the result.
            notifyListeners(File(result.filePath))
            result
        }
    }

    private fun notifyListeners(file: File) {
        if (listeners.isEmpty()) return
        val bytes = try {
            readCapture(file)
        } catch (e: Exception) {
            CameraKLogger.e("CameraK", "CameraK: Error reading the capture back for its listeners", e)
            return
        }
        // Each listener gets its own array, so one that changes or keeps it cannot corrupt what another
        // receives. The last one gets the array read from the file, after every copy is made from it.
        val snapshot = listeners.toTypedArray()
        snapshot.forEachIndexed { i, listener ->
            try {
                listener(if (i == snapshot.lastIndex) bytes else bytes.copyOf())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CameraKLogger.e("CameraK", "CameraK: A capture listener failed", e)
            }
        }
    }

    /**
     * Cycles the flash through the camera's modes. A desktop camera has only OFF, so the cycle stays
     * on it.
     */
    actual fun toggleFlashMode() {
        setFlashMode(FlashMode.OFF)
    }

    /**
     * Sets the flash mode. The mode is kept for the next start; while the camera is open it also
     * goes to the engine, which accepts only OFF on desktop.
     *
     * @param mode The desired [FlashMode]
     */
    actual fun setFlashMode(mode: FlashMode) {
        if (!updateDesired { it.with(flashMode = mode) }) return
        whileOpen { engine.flashCommand(mode) }
    }

    /**
     * @return the camera's flash mode: OFF, the only mode a desktop camera has
     */
    actual fun getFlashMode(): FlashMode? = engine.hardware.value.flashMode

    /**
     * Cycles the torch through the camera's modes. A desktop camera has no torch, so the cycle stays
     * on OFF.
     */
    actual fun toggleTorchMode() {
        setTorchMode(TorchMode.OFF)
    }

    /**
     * Sets the torch mode. The mode is kept for the next start; while the camera is open it also
     * goes to the engine, which accepts only OFF on desktop.
     *
     * @param mode The desired [TorchMode]
     */
    actual fun setTorchMode(mode: TorchMode) {
        if (!updateDesired { it.with(torchMode = mode) }) return
        whileOpen { engine.torchCommand(mode) }
    }

    /**
     * Gets the current torch mode.
     *
     * @return null as Desktop doesn't support torch mode
     */
    actual fun getTorchMode(): TorchMode? = null

    /**
     * Toggles the camera lens between FRONT and BACK.
     */
    actual fun toggleCameraLens() {
        // camera lens not available on desktop
    }

    /**
     * Gets the current camera lens.
     *
     * @return null as Desktop doesn't support camera lens switching
     */
    actual fun getCameraLens(): CameraLens? = null

    /**
     * Gets the current image format.
     *
     * @return The configured [ImageFormat]
     */
    actual fun getImageFormat(): ImageFormat = imageFormat

    // Reports the configured ratio so multiplatform UI can size consistently. The desktop capture
    // path itself renders the webcam's native frame (no ViewPort crop), so it doesn't enforce it.
    actual fun getAspectRatio(): AspectRatio = aspectRatio

    /**
     * Gets the current quality prioritization setting.
     *
     * @return The configured [QualityPrioritization] (always NONE on Desktop)
     */
    actual fun getQualityPrioritization(): QualityPrioritization = qualityPriority

    /**
     * Gets the current camera device type.
     *
     * @return The configured [CameraDeviceType] (always DEFAULT on Desktop)
     */
    actual fun getPreferredCameraDeviceType(): CameraDeviceType = CameraDeviceType.DEFAULT

    actual fun setPreferredCameraDeviceType(deviceType: CameraDeviceType) {
        // No-op on desktop — single camera
    }

    /** The engine's cameras: the enumerated devices, each OFF-only with a fixed zoom of 1. */
    actual fun getCameraCapabilities(): CameraCapabilities = engine.enumeratedCapabilities()

    /**
     * Sets the zoom ratio. The ratio is kept for the next start; while the camera is open it also
     * goes to the engine, which accepts only 1.0 on desktop.
     */
    actual fun setZoom(zoomRatio: Float) {
        if (!updateDesired { it.with(zoomRatio = zoomRatio) }) return
        whileOpen { engine.zoomCommand(zoomRatio) }
    }

    /**
     * Gets the current zoom ratio.
     *
     * @return the engine's zoom: 1.0, as a desktop camera's zoom is fixed
     */
    actual fun getZoom(): Float = engine.zoom.value.ratio

    /**
     * Gets the maximum zoom ratio.
     *
     * @return the engine's maximum: 1.0, as a desktop camera's zoom is fixed
     */
    actual fun getMaxZoom(): Float = engine.zoom.value.maxRatio

    /**
     * Starts the camera session on the engine. The engine has begun the session when this returns
     * (its grab loop exists), so a stop or cleanup that follows always reaches it, and a capture or
     * recording right after it reads its frames. Does nothing after [cleanup].
     */
    actual fun startSession() {
        if (closed.value) {
            CameraKLogger.w("CameraK", "CameraK: startSession() after cleanup() is ignored; create a new controller")
            return
        }
        // Undispatched: the engine begins the session on this thread, before the call returns.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { engine.start(desired.value) }
    }

    /**
     * Stops the camera session on the engine. The engine has ended the session when this returns;
     * the grab loop releases the camera once it leaves the native call it is in.
     */
    actual fun stopSession() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { engine.stop() }
    }

    /**
     * Adds a listener for image capture events. Listeners are a set: adding one never removes another.
     *
     * Each listener is called once per successful capture, after the photo is written, on the
     * `Dispatchers.IO` thread running [takePictureToFile] and before it returns, one listener after
     * another. A slow listener therefore delays the capture's result; hand long work to your own
     * dispatcher. Each listener receives its own copy of the photo's bytes, and one that throws is
     * logged and does not stop the others.
     *
     * @param listener The listener to add, receiving image data as [ByteArray].
     */
    actual fun addImageCaptureListener(listener: (ByteArray) -> Unit) {
        listeners.add(listener)
    }

    actual fun removeImageCaptureListener(listener: (ByteArray) -> Unit) {
        listeners.remove(listener)
    }

    actual fun getDeviceOrientation(): DeviceOrientation = DeviceOrientation.PORTRAIT

    actual fun setOnOrientationChangedListener(callback: ((DeviceOrientation) -> Unit)?) {
        // No-op on desktop
    }

    /** Kept for the next start; a desktop camera applies no orientation to its outputs. */
    actual fun setTargetOrientation(orientation: DeviceOrientation?) {
        updateDesired { it.with(targetOrientation = orientation) }
    }

    // In every state but closed, a 1.x setter changes the desired value (G3); false after cleanup().
    private inline fun updateDesired(change: (DesiredHardware) -> DesiredHardware): Boolean {
        if (closed.value) return false
        desired.update(change)
        return true
    }

    // Before Open the engine is not called: the value waits for the next start (G3).
    private inline fun <T> whileOpen(command: () -> CameraKResult<T>) {
        if (engine.state.value != EngineState.Open) return
        val result = command()
        if (result is CameraKResult.Failure) {
            // 1.x ignored these on desktop; the camera keeps flash and torch OFF and zoom 1.
            CameraKLogger.d("CameraK", "CameraK: The desktop camera has no such setting; it keeps OFF and zoom 1")
        }
    }

    /**
     * Ends the controller: stops any recording, closes the engine and cancels the controller's
     * scope. A controller is one with its engine, so this ends `Ready.controller`'s engine too.
     */
    actual fun cleanup() {
        closed.value = true
        stopVideoRecorderIfActive()
        engine.close()
        scope.cancel()
    }

    /**
     * Ends the controller as [cleanup] does, then suspends until its work has ended: the grab loop
     * has released the camera and any recording has stopped and released its recorder.
     */
    suspend fun closeAndJoin() {
        cleanup()
        scope.coroutineContext.job.join()
        engine.closeAndJoin()
    }

    actual suspend fun startRecording(configuration: VideoConfiguration): String = withContext(Dispatchers.IO) {
        recordingLock.withLock { startRecordingLocked(configuration) }
    }

    private fun startRecordingLocked(configuration: VideoConfiguration): String {
        // Reject a second start, as Android does: replacing activeRecording would orphan the first
        // recorder, which nothing could then stop or release.
        check(activeRecording.value == null) { "A recording is already in progress" }
        val outputPath = createVideoOutputPath(configuration)
        val recorder = recorderFactory(outputPath, configuration)
        // Encode at the camera's own frame size, so no frame is rescaled on the way in.
        cameraGrabber.frameSize?.let { (width, height) ->
            recorder.imageWidth = width
            recorder.imageHeight = height
        }
        try {
            recorder.start()
        } catch (e: Exception) {
            try {
                recorder.release()
            } catch (releaseFailure: Exception) {
                CameraKLogger.e("CameraK", "CameraK: Error releasing a recorder that failed to start", releaseFailure)
                e.addSuppressed(releaseFailure)
            }
            throw e
        }
        recordingOutputPath = outputPath
        isCurrentlyRecording = true
        val clock = RecordingClock(nanoClock())

        // The recording coroutine owns the recorder: it is the only code that calls record(), and it
        // stops and releases the recorder in its finally, after its last record() has returned.
        val finalized = CompletableDeferred<Exception?>()
        val frames = cameraGrabber.frames()
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            val converter = recordingConverter()
            var failure: Exception? = null
            var lastFrameNumber = -1L
            try {
                // Fed by the grab loop's frames; this coroutine never grabs from the camera itself.
                frames.collect { captured ->
                    // Frames captured while paused have no timestamp and are left out.
                    val timestamp = clock.timestampMicros(captured.captureNanos)
                    // The encoder rounds each timestamp to a frame number at its frame rate. A frame
                    // that rounds to the previous frame's number would repeat its pts, which the
                    // encoder rejects, so it is left out: a camera faster than the recording's frame
                    // rate is recorded at that rate.
                    val frameNumber = timestamp?.let { frameNumberAt(it, recorder.frameRate) }
                    if (isCurrentlyRecording &&
                        timestamp != null &&
                        frameNumber != null &&
                        frameNumber > lastFrameNumber
                    ) {
                        lastFrameNumber = frameNumber
                        try {
                            recorder.timestamp = timestamp
                            recorder.record(converter.convert(captured.image))
                        } catch (e: Exception) {
                            CameraKLogger.e("CameraK", "CameraK recording frame error", e)
                        }
                    }
                }
                // The frames ended without stopRecording(): the camera stopped or never ran, so the
                // recording ends here and stopRecording() reports it as failed.
                failure = IllegalStateException("The camera stopped before the recording was stopped")
                CameraKLogger.e("CameraK", "CameraK recording ended because the camera stopped", failure)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CameraKLogger.e("CameraK", "CameraK recording loop error", e)
                failure = e
            } finally {
                closeConverter(converter)
                finalized.complete(releaseRecorder(recorder) ?: failure)
            }
        }
        activeRecording.value = ActiveRecording(job, finalized, clock)

        return outputPath
    }

    actual suspend fun stopRecording(): VideoCaptureResult = withContext(Dispatchers.IO) {
        recordingLock.withLock {
            isCurrentlyRecording = false
            val recording = activeRecording.getAndSet(null)
            val durationMs = recording?.clock?.recordedMillis(nanoClock()) ?: 0L
            recording?.job?.cancelAndJoin()
            when (val failure = recording?.finalized?.await()) {
                null -> VideoCaptureResult.Success(recordingOutputPath ?: "", durationMs)
                else -> VideoCaptureResult.Error(failure)
            }
        }
    }

    // Frames captured while paused are dropped and the paused time is taken out of every later
    // timestamp, so the video cuts straight from the last frame before the pause to the first after.
    actual suspend fun pauseRecording() {
        activeRecording.value?.clock?.pause(nanoClock())
    }

    actual suspend fun resumeRecording() {
        activeRecording.value?.clock?.resume(nanoClock())
    }

    // Non-blocking: the recording coroutine stops and releases the recorder once its in-flight
    // record() returns.
    private fun stopVideoRecorderIfActive() {
        isCurrentlyRecording = false
        activeRecording.getAndSet(null)?.job?.cancel()
    }

    /** Stops then releases [recorder], returning the first failure (logged) or null. */
    private fun releaseRecorder(recorder: FrameRecorder): Exception? {
        var failure: Exception? = null
        try {
            recorder.stop()
        } catch (e: Exception) {
            CameraKLogger.e("CameraK", "CameraK: Error stopping recorder", e)
            failure = e
        }
        try {
            recorder.release()
        } catch (e: Exception) {
            CameraKLogger.e("CameraK", "CameraK: Error releasing recorder", e)
            failure = failure ?: e
        }
        return failure
    }

    private fun closeConverter(converter: JavaCvFrameConverter<BufferedImage>) {
        try {
            converter.close()
        } catch (e: Exception) {
            CameraKLogger.e("CameraK", "CameraK: Error closing the recording's frame converter", e)
        }
    }

    private class ActiveRecording(
        val job: Job,
        val finalized: CompletableDeferred<Exception?>,
        val clock: RecordingClock,
    )

    private fun createVideoOutputPath(config: VideoConfiguration): String {
        val timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val dir = config.outputDirectory?.let(::File) ?: File(File(userHome, moviesFolderName()), "CameraK")
        createOwnerOnlyDirectory(dir)
        return File(dir, "${config.filePrefix}_$timestamp.mp4").absolutePath
    }

    private fun pictureFolder(): File = when (directory) {
        Directory.PICTURES, Directory.DCIM -> File(userHome, "Pictures")
        Directory.DOCUMENTS -> File(userHome, "Documents")
    }
}

private fun DesiredHardware.with(
    flashMode: FlashMode = this.flashMode,
    torchMode: TorchMode = this.torchMode,
    zoomRatio: Float = this.zoomRatio,
    targetOrientation: DeviceOrientation? = this.targetOrientation,
) = DesiredHardware(selection, flashMode, torchMode, zoomRatio, targetOrientation)

private val FACADE_DESIRED = DesiredHardware(
    selection = CameraSelection.ByLens(CameraLens.BACK, CameraDeviceType.DEFAULT),
    flashMode = FlashMode.OFF,
    torchMode = TorchMode.OFF,
    zoomRatio = 1f,
    targetOrientation = null,
)

/**
 * The engine a controller built with a public constructor owns: over [customGrabber], or the
 * default camera at [targetResolution], on the default dispatchers. It never touches Main.
 */
private fun ownEngine(
    facade: CameraController,
    customGrabber: FrameGrabber?,
    targetResolution: Pair<Int, Int>?,
    horizontalFlip: Boolean,
    nanoClock: () -> Long,
): DesktopCameraEngine = DesktopCameraEngine(
    config = CameraConfiguration(targetResolution = targetResolution),
    environment = CameraKEnvironment(
        CameraKDispatchers(main = Dispatchers.Default, default = Dispatchers.Default, io = Dispatchers.IO),
    ),
    source = customGrabber?.let(::CustomGrabberSource) ?: DefaultDeviceSource(targetResolution),
    nanoClock = nanoClock,
    horizontalFlip = horizontalFlip,
    facade = { facade },
)

// user.home can be missing in sandboxed runtimes; fall back to the working directory, as the desktop
// image saver does.
private fun defaultUserHome(): File = File(System.getProperty("user.home") ?: System.getProperty("user.dir") ?: ".")

// macOS names the user's video folder Movies; Linux (XDG) and Windows name it Videos.
private fun moviesFolderName(): String =
    if (System.getProperty("os.name").lowercase().contains("mac")) "Movies" else "Videos"

private fun hasPosixPermissions(path: Path): Boolean = "posix" in path.fileSystem.supportedFileAttributeViews()

/**
 * Returns [folder], creating it (and any missing parents) if it does not exist. A folder this
 * creates is open only to its owner where the file system has POSIX permissions; an existing one
 * keeps its permissions.
 */
private fun createOwnerOnlyDirectory(folder: File): Path {
    val path = folder.toPath().toAbsolutePath()
    if (Files.isDirectory(path)) return path
    path.parent?.let { Files.createDirectories(it) }
    val ownerOnly = if (hasPosixPermissions(path)) {
        arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    } else {
        emptyArray()
    }
    return try {
        Files.createDirectory(path, *ownerOnly)
    } catch (e: FileAlreadyExistsException) {
        // Another capture created it first; use it if it is a folder.
        if (!Files.isDirectory(path)) throw e
        CameraKLogger.w("CameraK", "CameraK: Capture folder created concurrently, using it", e)
        path
    }
}

/**
 * Creates a new, empty file in [folder] (creating the folder too) whose name no other file has,
 * readable and writable only by its owner where the file system has POSIX permissions.
 */
internal fun createUniqueFile(folder: File, prefix: String, extension: String): File {
    val dir = createOwnerOnlyDirectory(folder)
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
    val ownerOnly = if (hasPosixPermissions(dir)) {
        arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    } else {
        emptyArray()
    }
    for (attempt in 0 until MAX_NAME_ATTEMPTS) {
        val suffix = if (attempt == 0) "" else "_$attempt"
        try {
            return Files.createFile(dir.resolve("${prefix}_$stamp$suffix.$extension"), *ownerOnly).toFile()
        } catch (e: FileAlreadyExistsException) {
            // Another capture in the same millisecond took this name; try the next suffix.
            CameraKLogger.w("CameraK", "CameraK: Capture file name taken, trying the next one", e)
        }
    }
    throw IOException("No free file name for $prefix in $MAX_NAME_ATTEMPTS attempts")
}

/**
 * An H.264 MP4 recorder for [configuration], sized from its quality until the controller sets the
 * camera's frame size. Desktop captures no microphone audio, so the file has no audio track even
 * when audio is asked for: an AAC track with no samples would be empty.
 */
internal fun createFrameRecorder(outputPath: String, configuration: VideoConfiguration): FrameRecorder {
    if (configuration.enableAudio) {
        CameraKLogger.w("CameraK", "CameraK: Desktop records no audio; the video has no audio track")
    }
    return FFmpegFrameRecorder(outputPath, configuration.quality.width, configuration.quality.height, 0).apply {
        videoCodec = avcodec.AV_CODEC_ID_H264
        format = "mp4"
        // The time base for the capture-time timestamps set on each frame.
        frameRate = RECORDING_FRAME_RATE
        videoBitrate = configuration.quality.bitrateBps
    }
}

/**
 * The frame number an encoder at [frameRate] gives a frame stamped [timestampMicros], as
 * `FFmpegFrameRecorder.setTimestamp` computes it. A recorder with no frame rate keeps the timestamp.
 */
private fun frameNumberAt(timestampMicros: Long, frameRate: Double): Long =
    if (frameRate > 0) Math.round(timestampMicros * frameRate / 1_000_000) else timestampMicros

/** Writes [image] to [file] as [format], throwing if no writer accepts it. */
internal fun writeImage(image: BufferedImage, format: ImageFormat, file: File) {
    if (format == ImageFormat.PNG) {
        if (!ImageIO.write(image, "png", file)) throw IOException("No PNG writer accepts this image")
        return
    }
    val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull()
        ?: throw IOException("No JPEG writer is installed")
    try {
        val params = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = JPEG_QUALITY
        }
        val stream = ImageIO.createImageOutputStream(file) ?: throw IOException("Cannot open the capture file")
        stream.use { out ->
            writer.output = out
            writer.write(null, IIOImage(image, null, null), params)
        }
    } finally {
        writer.dispose()
    }
}

// The encoder's frame rate: a camera faster than this is recorded at this rate.
private const val RECORDING_FRAME_RATE = 30.0

// Android compresses stills at 95 too, so a desktop JPEG matches it.
private const val JPEG_QUALITY = 0.95f
private const val MAX_NAME_ATTEMPTS = 100

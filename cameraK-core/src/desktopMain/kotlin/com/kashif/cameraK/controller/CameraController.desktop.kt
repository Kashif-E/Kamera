@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.capture.RecordedVideo
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
import com.kashif.cameraK.error.CameraKError
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bytedeco.javacv.FrameGrabber
import org.bytedeco.javacv.FrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.bytedeco.javacv.FrameConverter as JavaCvFrameConverter

/**
 * The desktop camera controller, backed by JavaCV. Until C1' it is a facade over a desktop engine
 * (§12.1): the engine owns the session and its one grab loop, which feeds the preview, captures
 * and recordings, and the facade's captures and recordings are the engine's (D3'), with 1.x
 * results. Captures are app-owned files under the user's home (`Documents/CameraK`, whatever the
 * [Directory]) and recordings are H.264 MP4 files without audio. A controller built with a public
 * constructor builds and owns its own engine; `Ready.controller` is the facade the bridge's engine
 * built over itself.
 */
actual class CameraController internal constructor(
    private val imageFormat: ImageFormat,
    private val aspectRatio: AspectRatio,
    sessionDispatcher: CoroutineDispatcher,
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
        aspectRatio = aspectRatio,
        sessionDispatcher = Dispatchers.IO,
        readCapture = File::readBytes,
        engine = { facade ->
            val own = OwnEngine(imageFormat, directory, customGrabber, targetResolution, horizontalFlip)
            own.build(facade, System::nanoTime, DesktopMedia())
        },
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
        aspectRatio = AspectRatio.RATIO_16_9,
        sessionDispatcher = sessionDispatcher,
        readCapture = readCapture,
        engine = { facade ->
            val media = DesktopMedia(userHome, recorderFactory, recordingConverter, writeCapture)
            OwnEngine(imageFormat, directory, customGrabber, null, horizontalFlip).build(facade, nanoClock, media)
        },
    )

    /** The facade an engine builds over itself: the bridge's, read through `Ready.controller`. */
    internal constructor(
        engine: DesktopCameraEngine,
        config: CameraConfiguration,
    ) : this(
        imageFormat = config.imageFormat,
        aspectRatio = config.aspectRatio,
        sessionDispatcher = Dispatchers.IO,
        readCapture = File::readBytes,
        engine = { engine },
    )

    /** The engine this controller is a facade over (§12.1). */
    internal val engine: DesktopCameraEngine = engine(this)

    // The controller's own scope: the session calls and the recording run in it, and cleanup() cancels it.
    private val scope = CoroutineScope(
        SupervisorJob() + sessionDispatcher +
            CoroutineExceptionHandler { _, e -> CameraKLogger.e("CameraK", "CameraK: Camera session failed", e) },
    )
    private val closed = atomic(false)

    // The last recording this facade started, until a stop claims its outcome (1.x).
    private val unclaimed = atomic<DesktopRecording?>(null)

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

    // Copy-on-write: listeners change rarely and are iterated once per capture, from the capture's
    // thread, while another thread may add or remove one. Empty means a capture reads nothing back.
    // Delivery: on the Dispatchers.IO thread running takePictureToFile, in order, before it returns.
    private val listeners = CopyOnWriteArrayList<(ByteArray) -> Unit>()

    /**
     * Writes the camera's next frame through the engine, as an app-owned file (§6.1): the
     * configuration's folder, else `Documents/CameraK` under the user's home, whatever the
     * [Directory]. A HEIC controller never starts (its start is `Unsupported(IMAGE_FORMAT)`), so its
     * capture has no frame and is an error.
     */
    actual suspend fun takePictureToFile(): ImageCaptureResult = withContext(Dispatchers.IO) {
        val image = when (val captured = engine.still(requireOpen = false)) {
            is CameraKResult.Success -> captured.value
            is CameraKResult.Failure -> return@withContext ImageCaptureResult.Error(
                when (val error = captured.error) {
                    CameraKError.NotReady, is CameraKError.Timeout -> IllegalStateException("No image available")
                    else -> error.asException("The capture failed")
                },
            )
        }
        // The photo is written; nothing a listener does can undo that or change the result.
        notifyListeners(File(image.path))
        ImageCaptureResult.SuccessWithFile(image.path)
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
     * Moves to the engine's next camera in enumeration order, back to the first after the last: a
     * desktop webcam faces no particular way, so the toggle cycles the devices. It goes through the
     * engine's `selectCamera` and keeps the camera for the next start. Does nothing with one camera.
     * A switch the engine refuses is logged at warning and changes nothing.
     */
    actual fun toggleCameraLens() {
        if (closed.value) return
        val lenses = engine.enumeratedCapabilities().allLenses
        if (lenses.size < 2) return
        val current = engine.hardware.value.camera?.id ?: (desired.value.selection as? CameraSelection.ById)?.id
        val next = lenses[(lenses.indexOfFirst { it.id == current }.coerceAtLeast(0) + 1) % lenses.size]
        // Undispatched: a refusal is known, and logged, before the call returns.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            when (val result = engine.selectCamera(CameraSelection.ById(next.id))) {
                is CameraKResult.Success -> desired.update {
                    it.with(selection = CameraSelection.ById(result.value.id))
                }
                is CameraKResult.Failure -> CameraKLogger.w(
                    "CameraK",
                    "CameraK: The camera switch was refused (${result.error::class.simpleName}); the camera is unchanged",
                )
            }
        }
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

    // Reports the configured ratio so multiplatform UI can size consistently. A controller built with
    // a public constructor keeps the webcam's own frame, as 1.x did; the bridge's engine crops every
    // frame to its configuration's ratio (D4').
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

    /**
     * Starts the engine's recording and returns its path; throws what the engine's start failed with
     * (a second start is an `IllegalStateException`). Desktop records no microphone audio: a
     * configuration that asks for it records video only, as 1.x did, where the engine's own start
     * is `Unsupported(AUDIO)`.
     */
    actual suspend fun startRecording(configuration: VideoConfiguration): String {
        val video = if (configuration.enableAudio) {
            CameraKLogger.w("CameraK", "CameraK: Desktop records no audio; the video has no audio track")
            configuration.copy(enableAudio = false)
        } else {
            configuration
        }
        return when (val started = engine.beginRecording(video, requireOpen = false)) {
            is CameraKResult.Success -> {
                unclaimed.value = started.value
                started.value.path
            }
            is CameraKResult.Failure -> throw when (val error = started.error) {
                is CameraKError.Busy -> IllegalStateException("A recording is already in progress")
                else -> error.asException("The recording did not start")
            }
        }
    }

    /**
     * Finalizes the engine's recording and returns its result. A recording this facade started that
     * ended by itself (a limit, an error, a lost camera, the session's stop) is the next stop's
     * result, once, as in 1.x; a recording started after it replaces it. A kept, playable file is a
     * success with its path, whatever ended the recording, as on Android (W6 G4). An error when
     * nothing is left to claim or the file is not playable.
     */
    actual suspend fun stopRecording(): VideoCaptureResult {
        val mine = unclaimed.getAndSet(null)
        var claimed = false
        try {
            val stopped = engine.stopRecording()
            val result = when {
                stopped !is CameraKResult.Failure || stopped.error !is CameraKError.InvalidState -> stopped.asLegacy()
                // Off the engine's books once it is finalizing, so its outcome is at most a moment away.
                mine != null -> mine.finalized.await().asLegacy()
                else -> VideoCaptureResult.Error(IllegalStateException("No active recording"))
            }
            claimed = true
            return result
        } finally {
            // A cancelled caller did not claim it: it stays for the next stop, unless a start replaced it.
            if (!claimed && mine != null) unclaimed.compareAndSet(null, mine)
        }
    }

    // Frames captured while paused are dropped and the paused time is taken out of every later
    // timestamp, so the video cuts straight from the last frame before the pause to the first after.
    actual suspend fun pauseRecording() {
        engine.pauseRecording()
    }

    actual suspend fun resumeRecording() {
        engine.resumeRecording()
    }
}

private fun CameraKResult<RecordedVideo>.asLegacy(): VideoCaptureResult = when (this) {
    is CameraKResult.Success -> VideoCaptureResult.Success(value.path, value.duration.inWholeMilliseconds)
    is CameraKResult.Failure -> VideoCaptureResult.Error(error.asException("The recording failed"))
}

// The 1.x results carry an exception: the platform's own when there is one.
private fun CameraKError.asException(what: String): Exception =
    cause as? Exception ?: IllegalStateException("$what: ${this::class.simpleName}", cause)

private fun DesiredHardware.with(
    selection: CameraSelection = this.selection,
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
private class OwnEngine(
    val imageFormat: ImageFormat,
    val directory: Directory,
    val customGrabber: FrameGrabber?,
    val targetResolution: Pair<Int, Int>?,
    val horizontalFlip: Boolean,
) {
    fun build(facade: CameraController, nanoClock: () -> Long, media: DesktopMedia) = DesktopCameraEngine(
        config = CameraConfiguration(
            imageFormat = imageFormat,
            directory = directory,
            targetResolution = targetResolution,
        ),
        environment = CameraKEnvironment(
            CameraKDispatchers(main = Dispatchers.Default, default = Dispatchers.Default, io = Dispatchers.IO),
        ),
        source = customGrabber?.let(::CustomGrabberSource) ?: DefaultDeviceSource(targetResolution),
        nanoClock = nanoClock,
        horizontalFlip = horizontalFlip,
        media = media,
        facade = { facade },
        cropToAspectRatio = false,
    )
}

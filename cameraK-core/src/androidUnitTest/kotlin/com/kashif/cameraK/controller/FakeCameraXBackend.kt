@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.MediaStore
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.ZoomState
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.concurrent.futures.ResolvableFuture
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.MutableLiveData
import com.google.common.util.concurrent.ListenableFuture
import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoQuality
import kotlinx.coroutines.Dispatchers
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A [CameraXBackend] with no camera behind it. The provider is delivered on the executor the
 * controller asks for, like ProcessCameraProvider's future listener, and still captures stay
 * pending until the test delivers their result.
 */
internal class FakeCameraXBackend : CameraXBackend {
    val binding = FakeCameraBinding()
    val captures = mutableListOf<PendingCapture>()
    val recordings = mutableListOf<FakeRecording>()

    /** The consumer each ImageAnalysis currently delivers its frames to. */
    val analyzers = mutableMapOf<ImageAnalysis, ImageAnalysis.Analyzer>()

    /** When set, CameraX refuses to start a recording with this, as Recorder does when it is in use. */
    var startFailure: RuntimeException? = null

    /**
     * When true, a new recording waits for [FakeRecording.deliverStarted]; otherwise CameraX reports
     * its Start on the callback executor right after the start, as it does once the encoder runs.
     */
    var holdRecordingStart = false

    /** When true, pause() and resume() wait for the test to deliver CameraX's Pause and Resume events. */
    var holdPauseResume = false

    /** The qualities [videoQualities] reports for every camera; all four unless a test narrows them. */
    var supportedQualities: Set<VideoQuality> = VideoQuality.entries.toSet()

    /** When set, the qualities of the camera with that id, in place of [supportedQualities]. */
    var qualitiesById: Map<String, Set<VideoQuality>>? = null

    /** The thread each video-quality query ran on, oldest first. */
    val qualityQueryThreads = mutableListOf<Thread>()

    /** The quality, bitrate and thread of each VideoCapture built, oldest first. */
    val videoCaptureBuilds = mutableListOf<VideoCaptureBuild>()

    override fun buildVideoCapture(quality: VideoQuality, bitrateBps: Int?): VideoCapture<Recorder> {
        val built = super.buildVideoCapture(quality, bitrateBps)
        videoCaptureBuilds += VideoCaptureBuild(quality, bitrateBps, Thread.currentThread(), built)
        return built
    }

    override fun videoQualities(cameraInfo: CameraInfo): Set<VideoQuality> {
        qualityQueryThreads += Thread.currentThread()
        val byId = qualitiesById ?: return supportedQualities
        val id = binding.cameras.last { it.camera.cameraInfo === cameraInfo }.id
        return byId[id].orEmpty()
    }

    /** The per-frame callback of each preview built, oldest first. */
    val previewFrameCallbacks = mutableListOf<() -> Unit>()

    /**
     * When set, the flash mode CameraX reports for an ImageCapture, in place of the mode it holds:
     * a read-back that differs from what was requested.
     */
    var flashReadBack: ((ImageCapture) -> Int)? = null

    override fun flashModeOf(imageCapture: ImageCapture): Int =
        flashReadBack?.invoke(imageCapture) ?: imageCapture.flashMode

    /** The preview of the latest bind produces a frame, reported on the calling thread. */
    fun deliverPreviewFrame() = previewFrameCallbacks.last().invoke()

    override fun requestProvider(context: Context, executor: Executor, onResult: (Result<CameraBinding>) -> Unit) {
        executor.execute { onResult(Result.success(binding)) }
    }

    override fun takePicture(
        imageCapture: ImageCapture,
        options: ImageCapture.OutputFileOptions,
        executor: Executor,
        callback: ImageCapture.OnImageSavedCallback,
    ) {
        captures += PendingCapture(options, executor, callback)
    }

    override fun setAnalyzer(analysis: ImageAnalysis, executor: Executor, analyzer: ImageAnalysis.Analyzer) {
        analyzers[analysis] = analyzer
    }

    override fun clearAnalyzer(analysis: ImageAnalysis) {
        analyzers -= analysis
    }

    override fun onPreviewFrame(previewBuilder: Preview.Builder, onFrame: () -> Unit) {
        previewFrameCallbacks += onFrame
    }

    override fun startRecording(
        videoCapture: VideoCapture<Recorder>,
        context: Context,
        output: FileOutputOptions,
        withAudio: Boolean,
        executor: Executor,
        onEvent: (RecordingEvent) -> Unit,
    ): RecordingSession {
        startFailure?.let { throw it }
        val recording = FakeRecording(output, withAudio, executor, {
            holdPauseResume
        }, onEvent).also { recordings += it }
        if (!holdRecordingStart) recording.deliverStarted()
        return recording
    }
}

/** A VideoCapture the engine had built: for [quality] at [bitrateBps], on [thread]. */
internal class VideoCaptureBuild(
    val quality: VideoQuality,
    val bitrateBps: Int?,
    val thread: Thread,
    val videoCapture: VideoCapture<Recorder>,
)

/**
 * A recording CameraX is running. It ends only when the test finalizes it, as CameraX ends a
 * recording with a Finalize event on the callback executor, whether or not stop() was called.
 * Unless [holdPauseResume] says so, pause() and resume() report CameraX's Pause and Resume events on that
 * executor, as CameraX does once the encoder has paused or resumed.
 */
internal class FakeRecording(
    /** What the controller asked CameraX to record into: the file and its limits. */
    val output: FileOutputOptions,
    /** Whether the controller asked for an audio track. */
    val withAudio: Boolean,
    private val executor: Executor,
    private val holdPauseResume: () -> Boolean,
    private val onEvent: (RecordingEvent) -> Unit,
) : RecordingSession {
    val outputFile: File get() = output.file

    var stopCalls = 0
        private set

    var pauseCalls = 0
        private set

    var resumeCalls = 0
        private set

    /** When set, stop() throws this, as Recording.stop() can once CameraX has torn the recorder down. */
    var stopFailure: RuntimeException? = null

    override fun stop() {
        stopCalls++
        stopFailure?.let { throw it }
    }

    override fun pause() {
        pauseCalls++
        if (!holdPauseResume()) deliverPaused()
    }

    override fun resume() {
        resumeCalls++
        if (!holdPauseResume()) deliverResumed()
    }

    /** CameraX reports the recording started. */
    fun deliverStarted() = executor.execute { onEvent(RecordingEvent.Started) }

    /** CameraX reports the recording paused. */
    fun deliverPaused() = executor.execute { onEvent(RecordingEvent.Paused) }

    /** CameraX reports the recording resumed. */
    fun deliverResumed() = executor.execute { onEvent(RecordingEvent.Resumed) }

    /**
     * CameraX ends the recording with [error], keeping [cause]. [written] is what reached the file
     * first; null leaves no file behind. A [playable] file is one MediaMetadataRetriever reads a
     * duration from, as a real MP4 would give; by default every file written is.
     */
    fun finalize(
        error: Int = VideoRecordEvent.Finalize.ERROR_NONE,
        written: ByteArray? = byteArrayOf(0, 0, 0, 24),
        recordedDurationNanos: Long = 1_500_000_000L,
        playable: Boolean = written != null,
        cause: Throwable? = null,
    ) {
        if (written != null) outputFile.writeBytes(written) else outputFile.delete()
        if (playable) {
            ShadowMediaMetadataRetriever.addMetadata(
                outputFile.absolutePath,
                MediaMetadataRetriever.METADATA_KEY_DURATION,
                (recordedDurationNanos / 1_000_000).coerceAtLeast(1L).toString(),
            )
        }
        executor.execute { onEvent(RecordingFinalized(error, cause, recordedDurationNanos)) }
    }
}

/**
 * A still CameraX has been asked for. Delivering it saves the bytes the way ImageCapture does: into
 * the requested file, or, for a MediaStore request, by inserting a row into the requested collection
 * (pending from API 29 until the bytes are written) and writing through the resolver.
 */
internal class PendingCapture(
    val options: ImageCapture.OutputFileOptions,
    private val executor: Executor,
    private val callback: ImageCapture.OnImageSavedCallback,
) {
    /** The requested file; fails for a MediaStore request. */
    val outputFile: File get() = checkNotNull(options.file) { "this capture was not requested into a file" }

    /** CameraX finished writing [bytes] to the destination and reports it on the callback executor. */
    fun deliverSaved(bytes: ByteArray) {
        val saved = options.file?.let { file ->
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            Uri.fromFile(file)
        } ?: saveToMediaStore(bytes)
        executor.execute { callback.onImageSaved(ImageCapture.OutputFileResults(saved)) }
    }

    /** CameraX reports the file saved, but it is gone by the time anyone reads it. */
    fun deliverSavedWithoutFile() {
        outputFile.delete()
        executor.execute { callback.onImageSaved(ImageCapture.OutputFileResults(Uri.fromFile(outputFile))) }
    }

    /**
     * Writing the image fails partway, for example on a full disk, after [written] reached the
     * destination. For a MediaStore request this is what CameraX 1.5.3 does
     * (`FileUtil.copyFileToMediaStore`): the row is inserted pending, the copy fails, the row is
     * marked not pending and the error is reported without deleting the row.
     */
    fun deliverWriteFailure(written: ByteArray) {
        options.file?.let { file ->
            file.parentFile?.mkdirs()
            file.writeBytes(written)
        } ?: run {
            val resolver = checkNotNull(options.contentResolver) { "neither a file nor a MediaStore request" }
            val values = ContentValues(options.contentValues ?: ContentValues())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) values.put(MediaStore.MediaColumns.IS_PENDING, 1)
            val row = checkNotNull(resolver.insert(checkNotNull(options.saveCollection), values))
            checkNotNull(resolver.openOutputStream(row)).use { it.write(written) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(row, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        }
        val error = ImageCaptureException(
            ImageCapture.ERROR_FILE_IO,
            "Failed to write to the output",
            java.io.IOException("No space left on device"),
        )
        executor.execute { callback.onError(error) }
    }

    private fun saveToMediaStore(bytes: ByteArray): Uri {
        val resolver = checkNotNull(options.contentResolver) { "neither a file nor a MediaStore request" }
        val values = ContentValues(options.contentValues ?: ContentValues())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        val row = checkNotNull(resolver.insert(checkNotNull(options.saveCollection), values))
        checkNotNull(resolver.openOutputStream(row)).use { it.write(bytes) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(row, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        return row
    }
}

/**
 * Tracks which use cases are bound, the way ProcessCameraProvider does: the camera is open while
 * any use case is bound to a started lifecycle. Binding to a destroyed lifecycle is refused, as
 * CameraX refuses it.
 */
internal class FakeCameraBinding : CameraBinding {
    val bound = linkedSetOf<UseCase>()
    var bindCalls = 0
        private set

    /** The camera each bind returned, oldest first. Each starts as CameraX opens one: torch off, zoom 1x. */
    val cameras = mutableListOf<FakeCamera>()

    /** The camera each bind asked for, oldest first. */
    val requested = mutableListOf<ResolvedCamera>()

    /** The thread each bind ran on, oldest first. */
    val bindThreads = mutableListOf<Thread>()

    /** The ViewPort of each use-case group bound, oldest first (null when the group had none). */
    val viewPorts = mutableListOf<ViewPort?>()

    /** Makes the camera each bind returns; a test swaps it for a camera with other hardware. */
    var newCamera: () -> FakeCamera = { FakeCamera() }

    /** When set, makes the camera a bind returns for the camera it asks for, in place of [newCamera]. */
    var cameraFor: ((ResolvedCamera) -> FakeCamera)? = null

    /** The next camera to open stays OPENING until the test reports it open. */
    var holdNextOpen = false

    /**
     * When true, a group that includes a VideoCapture is refused with IllegalArgumentException, as
     * CameraX refuses a combination of streams the camera cannot run.
     */
    var refuseVideoCapture = false

    /** Unbinds of use cases whose owner was already destroyed: an owner's final release of its camera. */
    var releaseCount = 0
        private set

    private val owners = mutableMapOf<UseCase, LifecycleOwner>()

    override fun unbind(vararg useCases: UseCase) {
        checkMainThread("unbind")
        if (useCases.any { owners[it]?.lifecycle?.currentState == Lifecycle.State.DESTROYED }) releaseCount++
        useCases.forEach { owners -= it }
        bound.removeAll(useCases.toSet())
        cameras.filter { camera -> camera.useCases.isNotEmpty() && camera.useCases.none { it in bound } }
            .forEach { it.closeForUnbind() }
    }

    override fun bindToLifecycle(owner: LifecycleOwner, camera: ResolvedCamera, useCaseGroup: UseCaseGroup): Camera {
        if (refuseVideoCapture && useCaseGroup.useCases.any { it is VideoCapture<*> }) {
            checkMainThread("bindToLifecycle")
            throw IllegalArgumentException("No supported surface combination for these use cases")
        }
        return bind(owner, camera, useCaseGroup.useCases).also { viewPorts += useCaseGroup.viewPort }
    }

    override fun bindToLifecycle(owner: LifecycleOwner, camera: ResolvedCamera, vararg useCases: UseCase): Camera =
        bind(owner, camera, useCases.toList())

    /** Binds as another CameraX consumer in the process would, through a selector of its own. */
    fun bindToLifecycle(owner: LifecycleOwner, selector: CameraSelector, vararg useCases: UseCase): Camera =
        bind(owner, ResolvedCamera.FacingDefault(CameraLens.BACK), useCases.toList())

    override fun cameraIdOf(camera: Camera): String = cameras.last { it.camera === camera }.id

    // CameraX 1.5.3 throws off the main thread (design §2.4), so a test fails where a device would.
    private fun checkMainThread(call: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "$call must be called on the main thread" }
    }

    private fun bind(owner: LifecycleOwner, resolved: ResolvedCamera, useCases: List<UseCase>): Camera {
        checkMainThread("bindToLifecycle")
        require(owner.lifecycle.currentState != Lifecycle.State.DESTROYED) {
            "Trying to create use case mediator with destroyed lifecycle."
        }
        bindCalls++
        requested += resolved
        bindThreads += Thread.currentThread()
        bound += useCases
        useCases.forEach { owners[it] = owner }
        val camera = cameraFor?.invoke(resolved) ?: newCamera()
        cameras += camera
        camera.useCases += useCases
        camera.follow(owner) { holdNextOpen.also { holdNextOpen = false } }
        return camera.camera
    }
}

/**
 * A bound camera. Its control records what the controller asks of the hardware and, like CameraX,
 * reports the result through the camera info's torch and zoom state; with [holdReports] it records
 * the request and leaves the reports to the test ([reportTorch], [reportZoom]). [minZoom] below 1 is
 * a logical camera that can switch to its ultra-wide lens. With [torchFailure] set, every torch
 * request fails through its future, as CameraX fails it when the camera is closed or has no flash
 * unit. Its camera state follows the lifecycle it is bound to, as CameraX opens a camera while that
 * lifecycle is started: OPENING, then OPEN unless the binding holds the open. While that lifecycle is
 * not started its use cases are detached, so, as CameraX's TorchControl and ZoomControl do, the
 * control refuses torch and zoom requests ("Camera is not active") and records none.
 */
internal class FakeCamera(
    val id: String = "0",
    private val maxZoom: Float = 8f,
    private val minZoom: Float = 1f,
    private val hasFlashUnit: Boolean = true,
    private val torchFailure: Throwable? = null,
    private val holdReports: Boolean = false,
) {
    /** The torch requests the camera accepted, oldest first. */
    val torchRequests = mutableListOf<Boolean>()

    /** The zoom ratios the camera accepted, oldest first. */
    val zoomRequests = mutableListOf<Float>()
    val torchState = MutableLiveData(TorchState.OFF)
    val zoomState = MutableLiveData<ZoomState>(FakeZoomState(1f, minZoom, maxZoom))
    val cameraState = MutableLiveData(CameraState.create(CameraState.Type.CLOSED))

    /** The use cases bound to this camera. */
    val useCases = mutableListOf<UseCase>()

    private var opened = false

    /** Reports [state] to the camera state's observers, on the calling thread. */
    fun report(state: CameraState) {
        cameraState.value = state
    }

    /** Opens the camera while [owner] is started; [holdOpen] says whether this open waits for the test. */
    fun follow(owner: LifecycleOwner, holdOpen: () -> Boolean) {
        owner.lifecycle.addObserver(
            LifecycleEventObserver { source, _ ->
                val started = source.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (started && !opened) {
                    opened = true
                    report(CameraState.create(CameraState.Type.OPENING))
                    if (!holdOpen()) report(CameraState.create(CameraState.Type.OPEN))
                } else if (!started && opened) {
                    opened = false
                    report(CameraState.create(CameraState.Type.CLOSED))
                }
            },
        )
    }

    fun closeForUnbind() {
        if (!opened) return
        opened = false
        report(CameraState.create(CameraState.Type.CLOSED))
    }

    /** CameraX reports the torch [on] or off, as its torch state observer would. */
    fun reportTorch(on: Boolean) {
        torchState.value = if (on) TorchState.ON else TorchState.OFF
    }

    /** CameraX reports the zoom [ratio], as its zoom state observer would. */
    fun reportZoom(ratio: Float) {
        zoomState.value = FakeZoomState(ratio.coerceIn(minZoom, maxZoom), minZoom, maxZoom)
    }

    private val control = proxy<CameraControl> { method, args ->
        when (method.name) {
            "enableTorch" -> {
                val on = args!![0] as Boolean
                when {
                    !opened -> failedFuture(IllegalStateException("Camera is not active."))
                    torchFailure != null -> {
                        torchRequests += on
                        failedFuture(torchFailure)
                    }
                    else -> {
                        torchRequests += on
                        if (!holdReports) reportTorch(on)
                        completedFuture()
                    }
                }
            }
            "setZoomRatio" -> {
                val ratio = args!![0] as Float
                if (!opened) {
                    failedFuture(IllegalStateException("Camera is not active."))
                } else {
                    zoomRequests += ratio
                    if (!holdReports) reportZoom(ratio)
                    completedFuture()
                }
            }
            else -> completedFuture()
        }
    }

    private val info = proxy<CameraInfo> { method, _ ->
        when (method.name) {
            "hasFlashUnit" -> hasFlashUnit
            "getTorchState" -> torchState
            "getZoomState" -> zoomState
            "getCameraState" -> cameraState
            else -> null
        }
    }

    val camera: Camera = proxy { method, _ ->
        when (method.name) {
            "getCameraControl" -> control
            "getCameraInfo" -> info
            else -> if (method.returnType == java.lang.Boolean.TYPE) false else null
        }
    }
}

private class FakeZoomState(private val ratio: Float, private val min: Float, private val max: Float) : ZoomState {
    override fun getZoomRatio() = ratio

    override fun getMaxZoomRatio() = max

    override fun getMinZoomRatio() = min

    override fun getLinearZoom() = (ratio - min) / (max - min)
}

private fun completedFuture(): ListenableFuture<Void> = ResolvableFuture.create<Void>().apply { set(null) }

private fun failedFuture(cause: Throwable): ListenableFuture<Void> =
    ResolvableFuture.create<Void>().apply { setException(cause) }

private inline fun <reified T> proxy(crossinline answer: (Method, Array<Any?>?) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(self)
            "equals" -> self === args?.firstOrNull()
            "toString" -> "Fake${T::class.java.simpleName}"
            else -> answer(method, args)
        }
    } as T

/** A host lifecycle that has reached RESUMED, like the activity hosting the camera screen. */
internal class ResumedLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle get() = registry
}

internal fun testController(
    context: Context,
    backend: CameraXBackend,
    lifecycleOwner: LifecycleOwner = ResumedLifecycleOwner(),
    imageProcessingExecutor: ExecutorService = Executors.newFixedThreadPool(2),
    analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
    directory: Directory = Directory.DOCUMENTS,
    imageFormat: ImageFormat = ImageFormat.JPEG,
    torchMode: TorchMode = TorchMode.OFF,
    qualityPriority: QualityPrioritization = QualityPrioritization.BALANCED,
    flashMode: FlashMode = FlashMode.OFF,
    mirrorFrontCamera: Boolean = false,
    started: Boolean = true,
) = CameraController(
    context = context,
    lifecycleOwner = lifecycleOwner,
    flashMode = flashMode,
    torchMode = torchMode,
    cameraLens = CameraLens.BACK,
    imageFormat = imageFormat,
    qualityPriority = qualityPriority,
    directory = directory,
    cameraDeviceType = CameraDeviceType.DEFAULT,
    aspectRatio = AspectRatio.RATIO_4_3,
    targetResolution = null,
    mirrorFrontCamera = mirrorFrontCamera,
    backend = backend,
    imageProcessingExecutor = imageProcessingExecutor,
    analyzerExecutor = analyzerExecutor,
    // Every engine dispatcher on the main looper, which the tests run with idleMainLooper().
    environment = CameraKEnvironment(
        dispatchers = CameraKDispatchers(main = Dispatchers.Main, default = Dispatchers.Main, io = Dispatchers.Main),
    ),
).also {
    // A 1.x controller opens the camera from startSession, as the 1.x holder's initialize calls it.
    if (started) it.startSession()
}

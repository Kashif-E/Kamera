package com.kashif.cameraK.controller

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
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
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
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

    /** The per-frame callback of each preview built, oldest first. */
    val previewFrameCallbacks = mutableListOf<() -> Unit>()

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
        onFinalized: (RecordingFinalized) -> Unit,
    ): RecordingSession {
        startFailure?.let { throw it }
        return FakeRecording(output, executor, onFinalized).also { recordings += it }
    }
}

/**
 * A recording CameraX is running. It ends only when the test finalizes it, as CameraX ends a
 * recording with a Finalize event on the callback executor, whether or not stop() was called.
 */
internal class FakeRecording(
    /** What the controller asked CameraX to record into: the file and its limits. */
    val output: FileOutputOptions,
    private val executor: Executor,
    private val onFinalized: (RecordingFinalized) -> Unit,
) : RecordingSession {
    val outputFile: File get() = output.file

    var stopCalls = 0
        private set

    override fun stop() {
        stopCalls++
    }

    override fun pause() = Unit

    override fun resume() = Unit

    /**
     * CameraX ends the recording with [error]. [written] is what reached the file first; null
     * leaves no file behind.
     */
    fun finalize(
        error: Int = VideoRecordEvent.Finalize.ERROR_NONE,
        written: ByteArray? = byteArrayOf(0, 0, 0, 24),
        recordedDurationNanos: Long = 1_500_000_000L,
    ) {
        if (written != null) outputFile.writeBytes(written) else outputFile.delete()
        executor.execute { onFinalized(RecordingFinalized(error, null, recordedDurationNanos)) }
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

    /** Unbinds of use cases whose owner was already destroyed: an owner's final release of its camera. */
    var releaseCount = 0
        private set

    private val owners = mutableMapOf<UseCase, LifecycleOwner>()

    override fun unbind(vararg useCases: UseCase) {
        if (useCases.any { owners[it]?.lifecycle?.currentState == Lifecycle.State.DESTROYED }) releaseCount++
        useCases.forEach { owners -= it }
        bound.removeAll(useCases.toSet())
        cameras.filter { camera -> camera.useCases.isNotEmpty() && camera.useCases.none { it in bound } }
            .forEach { it.closeForUnbind() }
    }

    override fun bindToLifecycle(owner: LifecycleOwner, camera: ResolvedCamera, useCaseGroup: UseCaseGroup): Camera =
        bind(owner, camera, useCaseGroup.useCases).also { viewPorts += useCaseGroup.viewPort }

    override fun bindToLifecycle(owner: LifecycleOwner, camera: ResolvedCamera, vararg useCases: UseCase): Camera =
        bind(owner, camera, useCases.toList())

    /** Binds as another CameraX consumer in the process would, through a selector of its own. */
    fun bindToLifecycle(owner: LifecycleOwner, selector: CameraSelector, vararg useCases: UseCase): Camera =
        bind(owner, ResolvedCamera.FacingDefault(CameraLens.BACK), useCases.toList())

    override fun cameraIdOf(camera: Camera): String = cameras.last { it.camera === camera }.id

    private fun bind(owner: LifecycleOwner, resolved: ResolvedCamera, useCases: List<UseCase>): Camera {
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
 * reports the result through the camera info's torch and zoom state. [minZoom] below 1 is a logical
 * camera that can switch to its ultra-wide lens. With [torchFailure] set, every torch request fails
 * through its future, as CameraX fails it when the camera is closed or has no flash unit. Its camera
 * state follows the lifecycle it is bound to, as CameraX opens a camera while that lifecycle is
 * started: OPENING, then OPEN unless the binding holds the open.
 */
internal class FakeCamera(
    val id: String = "0",
    private val maxZoom: Float = 8f,
    private val minZoom: Float = 1f,
    private val hasFlashUnit: Boolean = true,
    private val torchFailure: Throwable? = null,
) {
    val torchRequests = mutableListOf<Boolean>()
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

    private val control = proxy<CameraControl> { method, args ->
        when (method.name) {
            "enableTorch" -> {
                val on = args!![0] as Boolean
                torchRequests += on
                if (torchFailure != null) {
                    failedFuture(torchFailure)
                } else {
                    torchState.value = if (on) TorchState.ON else TorchState.OFF
                    completedFuture()
                }
            }
            "setZoomRatio" -> {
                val ratio = args!![0] as Float
                zoomRequests += ratio
                zoomState.value = FakeZoomState(ratio.coerceIn(minZoom, maxZoom), minZoom, maxZoom)
                completedFuture()
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
)

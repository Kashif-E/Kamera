package com.kashif.cameraK.controller

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.QualityPrioritization
import com.kashif.cameraK.enums.TorchMode
import java.io.File
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

    override fun unbindAll() {
        bound.clear()
    }

    override fun unbind(vararg useCases: UseCase) {
        bound.removeAll(useCases.toSet())
    }

    override fun bindToLifecycle(owner: LifecycleOwner, selector: CameraSelector, useCaseGroup: UseCaseGroup): Camera =
        bind(owner, useCaseGroup.useCases)

    override fun bindToLifecycle(owner: LifecycleOwner, selector: CameraSelector, vararg useCases: UseCase): Camera =
        bind(owner, useCases.toList())

    private fun bind(owner: LifecycleOwner, useCases: List<UseCase>): Camera {
        require(owner.lifecycle.currentState != Lifecycle.State.DESTROYED) {
            "Trying to create use case mediator with destroyed lifecycle."
        }
        bindCalls++
        bound += useCases
        return fakeCamera()
    }
}

private fun fakeCamera(): Camera = Proxy.newProxyInstance(Camera::class.java.classLoader, arrayOf(Camera::class.java)) {
        _,
        method,
        _,
    ->
    when (method.returnType) {
        java.lang.Boolean.TYPE -> false
        else -> null
    }
} as Camera

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
) = CameraController(
    context = context,
    lifecycleOwner = lifecycleOwner,
    flashMode = FlashMode.OFF,
    torchMode = TorchMode.OFF,
    cameraLens = CameraLens.BACK,
    imageFormat = imageFormat,
    qualityPriority = QualityPrioritization.BALANCED,
    directory = directory,
    cameraDeviceType = CameraDeviceType.DEFAULT,
    aspectRatio = AspectRatio.RATIO_4_3,
    targetResolution = null,
    mirrorFrontCamera = false,
    backend = backend,
    imageProcessingExecutor = imageProcessingExecutor,
    analyzerExecutor = analyzerExecutor,
)

package com.kashif.cameraK.controller

import android.content.Context
import android.net.Uri
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
        outputFile: File,
        metadata: ImageCapture.Metadata,
        executor: Executor,
        callback: ImageCapture.OnImageSavedCallback,
    ) {
        captures += PendingCapture(outputFile, executor, callback)
    }
}

internal class PendingCapture(
    val outputFile: File,
    private val executor: Executor,
    private val callback: ImageCapture.OnImageSavedCallback,
) {
    /** CameraX finished writing [bytes] to the output file and reports it on the callback executor. */
    fun deliverSaved(bytes: ByteArray) {
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(bytes)
        executor.execute { callback.onImageSaved(ImageCapture.OutputFileResults(Uri.fromFile(outputFile))) }
    }

    /** CameraX reports the file saved, but it is gone by the time anyone reads it. */
    fun deliverSavedWithoutFile() {
        outputFile.delete()
        executor.execute { callback.onImageSaved(ImageCapture.OutputFileResults(Uri.fromFile(outputFile))) }
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
) = CameraController(
    context = context,
    lifecycleOwner = lifecycleOwner,
    flashMode = FlashMode.OFF,
    torchMode = TorchMode.OFF,
    cameraLens = CameraLens.BACK,
    imageFormat = ImageFormat.JPEG,
    qualityPriority = QualityPrioritization.BALANCED,
    directory = Directory.DOCUMENTS,
    cameraDeviceType = CameraDeviceType.DEFAULT,
    aspectRatio = AspectRatio.RATIO_4_3,
    targetResolution = null,
    mirrorFrontCamera = false,
    backend = backend,
    imageProcessingExecutor = imageProcessingExecutor,
    analyzerExecutor = analyzerExecutor,
)

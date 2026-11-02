package com.kashif.cameraK.controller

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor

/**
 * The slice of [ProcessCameraProvider] that [CameraController] binds through. The provider is shared
 * by the whole process, so it offers no unbindAll(): a controller unbinds only what it bound.
 */
internal interface CameraBinding {
    fun unbind(vararg useCases: UseCase)

    fun bindToLifecycle(owner: LifecycleOwner, selector: CameraSelector, useCaseGroup: UseCaseGroup): Camera

    fun bindToLifecycle(owner: LifecycleOwner, selector: CameraSelector, vararg useCases: UseCase): Camera
}

/**
 * The CameraX entry points [CameraController] calls that need camera hardware: obtaining the
 * process camera provider and taking a still. Production uses [CameraXBackend.Default]; unit tests
 * substitute a backend that runs without a camera.
 */
internal interface CameraXBackend {
    /**
     * Requests the camera provider and runs [onResult] on [executor] with it, or with the failure.
     */
    fun requestProvider(context: Context, executor: Executor, onResult: (Result<CameraBinding>) -> Unit)

    fun takePicture(
        imageCapture: ImageCapture,
        options: ImageCapture.OutputFileOptions,
        executor: Executor,
        callback: ImageCapture.OnImageSavedCallback,
    )

    object Default : CameraXBackend {
        override fun requestProvider(context: Context, executor: Executor, onResult: (Result<CameraBinding>) -> Unit) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({ onResult(runCatching { ProcessCameraBinding(future.get()) }) }, executor)
        }

        override fun takePicture(
            imageCapture: ImageCapture,
            options: ImageCapture.OutputFileOptions,
            executor: Executor,
            callback: ImageCapture.OnImageSavedCallback,
        ) {
            imageCapture.takePicture(options, executor, callback)
        }
    }
}

private class ProcessCameraBinding(private val provider: ProcessCameraProvider) : CameraBinding {
    override fun unbind(vararg useCases: UseCase) = provider.unbind(*useCases)

    override fun bindToLifecycle(owner: LifecycleOwner, selector: CameraSelector, useCaseGroup: UseCaseGroup): Camera =
        provider.bindToLifecycle(owner, selector, useCaseGroup)

    override fun bindToLifecycle(owner: LifecycleOwner, selector: CameraSelector, vararg useCases: UseCase): Camera =
        provider.bindToLifecycle(owner, selector, *useCases)
}

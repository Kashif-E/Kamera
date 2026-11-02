package com.kashif.cameraK.controller

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
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

/** A recording CameraX is running. */
internal interface RecordingSession {
    fun stop()

    fun pause()

    fun resume()
}

/**
 * How a recording ended: CameraX's [VideoRecordEvent.Finalize], reduced to what the controller reads.
 * [error] is one of the `VideoRecordEvent.Finalize.ERROR_*` codes.
 */
internal class RecordingFinalized(val error: Int, val cause: Throwable?, val recordedDurationNanos: Long) {
    val hasError: Boolean get() = error != VideoRecordEvent.Finalize.ERROR_NONE
}

/**
 * The CameraX entry points [CameraController] calls that need camera hardware: obtaining the
 * process camera provider, taking a still and recording video. Production uses
 * [CameraXBackend.Default]; unit tests substitute a backend that runs without a camera.
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

    /**
     * Starts recording [videoCapture] into [output] and runs [onFinalized] on [executor] when the
     * recording ends, however it ends.
     */
    fun startRecording(
        videoCapture: VideoCapture<Recorder>,
        context: Context,
        output: FileOutputOptions,
        withAudio: Boolean,
        executor: Executor,
        onFinalized: (RecordingFinalized) -> Unit,
    ): RecordingSession

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

        @SuppressLint("MissingPermission") // The caller asks for audio only when RECORD_AUDIO is granted.
        override fun startRecording(
            videoCapture: VideoCapture<Recorder>,
            context: Context,
            output: FileOutputOptions,
            withAudio: Boolean,
            executor: Executor,
            onFinalized: (RecordingFinalized) -> Unit,
        ): RecordingSession {
            val pending = videoCapture.output.prepareRecording(context, output)
            if (withAudio) pending.withAudioEnabled()
            val recording = pending.start(executor) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    onFinalized(
                        RecordingFinalized(event.error, event.cause, event.recordingStats.recordedDurationNanos),
                    )
                }
            }
            return object : RecordingSession {
                override fun stop() = recording.stop()

                override fun pause() = recording.pause()

                override fun resume() = recording.resume()
            }
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

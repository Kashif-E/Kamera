package com.kashif.cameraK.controller

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
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
 * by the whole process, so it offers no unbindAll(): a controller unbinds only what it bound. A bind
 * names the [ResolvedCamera]; the binding turns it into CameraX's selector.
 */
internal interface CameraBinding {
    fun unbind(vararg useCases: UseCase)

    fun bindToLifecycle(owner: LifecycleOwner, camera: ResolvedCamera, useCaseGroup: UseCaseGroup): Camera

    fun bindToLifecycle(owner: LifecycleOwner, camera: ResolvedCamera, vararg useCases: UseCase): Camera

    /** The camera id of [camera], one a bind returned. */
    fun cameraIdOf(camera: Camera): String
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

    /** Makes [analyzer] the consumer of [analysis]'s frames, run on [executor]. */
    fun setAnalyzer(analysis: ImageAnalysis, executor: Executor, analyzer: ImageAnalysis.Analyzer)

    /** Stops delivering [analysis]'s frames to any consumer. */
    fun clearAnalyzer(analysis: ImageAnalysis)

    /**
     * Runs [onFrame] on the camera's thread for each frame the preview built from [previewBuilder]
     * produces, from the first one on.
     */
    fun onPreviewFrame(previewBuilder: Preview.Builder, onFrame: () -> Unit)

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

        override fun setAnalyzer(analysis: ImageAnalysis, executor: Executor, analyzer: ImageAnalysis.Analyzer) {
            analysis.setAnalyzer(executor, analyzer)
        }

        override fun clearAnalyzer(analysis: ImageAnalysis) = analysis.clearAnalyzer()

        @OptIn(ExperimentalCamera2Interop::class)
        override fun onPreviewFrame(previewBuilder: Preview.Builder, onFrame: () -> Unit) {
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) = onFrame()
                },
            )
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

    override fun bindToLifecycle(owner: LifecycleOwner, camera: ResolvedCamera, useCaseGroup: UseCaseGroup): Camera =
        provider.bindToLifecycle(owner, camera.toCameraSelector(), useCaseGroup)

    override fun bindToLifecycle(owner: LifecycleOwner, camera: ResolvedCamera, vararg useCases: UseCase): Camera =
        provider.bindToLifecycle(owner, camera.toCameraSelector(), *useCases)

    @OptIn(ExperimentalCamera2Interop::class)
    override fun cameraIdOf(camera: Camera): String = Camera2CameraInfo.from(camera.cameraInfo).cameraId
}

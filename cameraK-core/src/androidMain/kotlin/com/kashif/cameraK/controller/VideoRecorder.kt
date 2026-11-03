package com.kashif.cameraK.controller

import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoCaptureResult
import com.kashif.cameraK.video.VideoConfiguration
import com.kashif.cameraK.video.VideoQuality
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "CameraK"

/** How long a stop waits for CameraX's Finalize, and a recording bind for the camera to be bound. */
internal const val STOP_FINALIZE_TIMEOUT_MS = 5000L

/** What [VideoRecorder] needs from the session that binds the camera. */
internal interface VideoSessionHost {
    val isReleased: Boolean

    /** Whether a bind has given the session its camera. */
    val isBound: Boolean

    /** Rebinds the bound camera and waits for the bind, for at most [STOP_FINALIZE_TIMEOUT_MS]. */
    suspend fun rebindAndAwait()

    /** CameraX ended the active recording by itself and VideoCapture was dropped: rebind for full-FOV stills. */
    fun rebindAfterRecordingEndedByCameraX()

    /** No recording is active any more. */
    fun onNoActiveRecording()
}

/**
 * Video recording: a VideoCapture bound only while a recording needs it, one CameraX recording per
 * start, and its Finalize mapped by [FinalizeMapper] into the result the next stop returns.
 */
internal class VideoRecorder(
    private val context: Context,
    private val backend: CameraXBackend,
    private val files: OutputFileFactory,
    private val host: VideoSessionHost,
) {
    /** The VideoCapture use case of the current bind, or null when it has none. */
    var videoCapture: VideoCapture<Recorder>? = null
        private set

    private var activeRecording: RecordingSession? = null

    // The outcome of the current recording, completed by its Finalize however the recording ended,
    // and kept until a stopRecording() claims it. A recording CameraX ends by itself (source
    // inactive, disk full, a limit reached) is still reported by the next stop.
    private var recordingOutcome: CompletableDeferred<VideoCaptureResult>? = null

    // VideoCapture is only bound while recording. A 16:9 VideoCapture in the photo UseCaseGroup
    // shares the ViewPort and shrinks the common field of view, so every still gets cropped/zoomed
    // (#136) — unless StreamSharing happens to merge it. Binding it lazily keeps photos full-FOV.
    @Volatile
    private var includeVideoUseCase = false

    @Volatile
    private var pendingVideoQuality = VideoQuality.FHD

    val isRecording: Boolean get() = activeRecording != null

    /** Builds the VideoCapture for the next bind when a recording needs one, and drops it otherwise. */
    fun configureForBind() {
        if (includeVideoUseCase) configureVideoCapture(pendingVideoQuality) else videoCapture = null
    }

    /** The VideoCapture the next bind's use-case group includes, or null. */
    fun useCaseForBind(): VideoCapture<Recorder>? = if (includeVideoUseCase) videoCapture else null

    private fun configureVideoCapture(quality: VideoQuality) {
        try {
            val cameraXQuality = when (quality) {
                VideoQuality.SD -> Quality.SD
                VideoQuality.HD -> Quality.HD
                VideoQuality.FHD -> Quality.FHD
                VideoQuality.UHD -> Quality.UHD
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        cameraXQuality,
                        FallbackStrategy.higherQualityOrLowerThan(cameraXQuality),
                    ),
                )
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
        } catch (e: Exception) {
            CameraKLogger.w(TAG, "Building the VideoCapture use case failed; binding without it", e)
            videoCapture = null
        }
    }

    suspend fun startRecording(configuration: VideoConfiguration): String {
        // Reject a second start: starting again would overwrite activeRecording and orphan the
        // first recording's session (it could never be stopped/finalized).
        if (activeRecording != null) throw IllegalStateException("A recording is already in progress")
        // VideoCapture is bound lazily (it isn't part of the photo session — see includeVideoUseCase).
        // Bind it now and wait for the rebind before recording.
        val videoBind = videoCapture == null
        if (videoBind) {
            if (!host.isBound) throw IllegalStateException("Camera preview not ready for recording")
            pendingVideoQuality = configuration.quality
            includeVideoUseCase = true
        }
        return try {
            // Don't hang forever if the bind fails. On timeout, startRecordingInternal reports a
            // clean error.
            if (videoBind) host.rebindAndAwait()
            startRecordingInternal(configuration)
        } catch (e: Exception) {
            // Recording never started, whether it failed or the caller was cancelled during the bind:
            // drop the lazily-bound VideoCapture and rebind, otherwise the flag stays set and every
            // later photo silently reverts to the cropped FOV (#136).
            if (includeVideoUseCase) {
                includeVideoUseCase = false
                withContext(NonCancellable) { if (host.isBound) host.rebindAndAwait() }
            }
            throw e
        }
    }

    private suspend fun startRecordingInternal(configuration: VideoConfiguration): String =
        suspendCancellableCoroutine { cont ->
            val vc = videoCapture ?: run {
                cont.resumeWithException(IllegalStateException("VideoCapture use case not available"))
                return@suspendCancellableCoroutine
            }

            val outputFile = files.videoFile(configuration)
            val outcome = CompletableDeferred<VideoCaptureResult>()
            // CameraX stops at either limit and finalizes a playable file (see FinalizeMapper).
            val outputOptions = FileOutputOptions.Builder(outputFile)
                .setDurationLimitMillis(configuration.maxDurationMs)
                .setFileSizeLimit(configuration.maxFileSizeBytes)
                .build()

            val hasAudioPermission = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

            // Finalize always arrives on the main executor, after the session below is assigned.
            var session: RecordingSession? = null
            session = try {
                backend.startRecording(
                    videoCapture = vc,
                    context = context,
                    output = outputOptions,
                    withAudio = configuration.enableAudio && hasAudioPermission,
                    executor = ContextCompat.getMainExecutor(context),
                ) { event ->
                    outcome.complete(finalizedResult(event, outputFile))
                    // stopRecording() clears activeRecording before it waits, so a recording that
                    // is still active here was ended by CameraX, with no stop call to restore the
                    // session. A late Finalize of an earlier recording must not touch a newer one.
                    if (activeRecording === session) {
                        activeRecording = null
                        if (includeVideoUseCase && !host.isReleased) {
                            // Drop VideoCapture so stills are full-FOV again (#136).
                            includeVideoUseCase = false
                            host.rebindAfterRecordingEndedByCameraX()
                        }
                    }
                    if (activeRecording == null) host.onNoActiveRecording()
                }
            } catch (e: Exception) {
                // CameraX refused to start: nothing records, so nothing is left for a stop to await.
                cont.resumeWithException(e)
                return@suspendCancellableCoroutine
            }

            if (recordingOutcome != null) {
                CameraKLogger.w(TAG, "The last recording's outcome was never claimed by stopRecording(); discarding it")
            }
            recordingOutcome = outcome
            activeRecording = session
            cont.resume(outputFile.absolutePath)
        }

    suspend fun stopRecording(): VideoCaptureResult {
        val outcome = recordingOutcome
            ?: return VideoCaptureResult.Error(IllegalStateException("No active recording"))
        recordingOutcome = null
        activeRecording?.stop()
        activeRecording = null
        var claimed = false
        try {
            // Don't wait forever: if the CameraX finalize event never arrives this would hang the
            // caller and leave isRecording stuck true.
            val result = withTimeoutOrNull(STOP_FINALIZE_TIMEOUT_MS) { outcome.await() }
                ?: VideoCaptureResult.Error(IllegalStateException("Timed out waiting for recording to finalize"))
            claimed = true
            return result
        } finally {
            // A cancelled caller did not claim the outcome: keep it for the next stop, unless a new
            // recording has already started.
            if (!claimed && recordingOutcome == null && activeRecording == null) recordingOutcome = outcome
            // Drop VideoCapture and rebind back to the full-FOV photo session, also when the caller
            // was cancelled. Await the rebind before returning: a fire-and-forget rebind lets an
            // immediate photo (or a quick startRecording) race the pending bind — capturing on the
            // old UseCaseGroup, or the late rebind unbinding a freshly-started recording's use cases.
            if (includeVideoUseCase) {
                includeVideoUseCase = false
                withContext(NonCancellable) { if (host.isBound) host.rebindAndAwait() }
            }
        }
    }

    /** What a Finalize means for the caller; a recording ended by an error but still playable is logged. */
    private fun finalizedResult(event: RecordingFinalized, file: File): VideoCaptureResult {
        val playable = file.isFile && file.length() > 0
        return when (val outcome = FinalizeMapper.map(event, playable)) {
            is FinalizeOutcome.Failed -> {
                CameraKLogger.e(TAG, "Recording failed with CameraX error ${outcome.error}", event.cause)
                VideoCaptureResult.Error(Exception("Recording error code: ${outcome.error}", event.cause))
            }
            is FinalizeOutcome.Recorded -> {
                outcome.endedBy?.let {
                    CameraKLogger.w(TAG, "Recording ended by CameraX error $it; its file is playable", event.cause)
                }
                // Notify MediaStore so video appears in Gallery
                notifyMediaStoreVideo(file)
                VideoCaptureResult.Success(filePath = file.absolutePath, durationMs = outcome.durationMs)
            }
        }
    }

    fun pauseRecording() {
        activeRecording?.pause()
    }

    fun resumeRecording() {
        activeRecording?.resume()
    }

    private fun notifyMediaStoreVideo(file: File) {
        try {
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("video/mp4"),
                null,
            )
        } catch (e: Exception) {
            CameraKLogger.e(TAG, "Notifying MediaStore of a recorded video failed", e)
        }
    }

    /** Stops the active recording while its VideoCapture is still bound, so it finalizes cleanly. */
    fun stopForRelease() {
        activeRecording?.stop()
        activeRecording = null
    }

    /** Completes an outcome no stop has claimed with the released-controller error. */
    fun failUnclaimedOutcome() {
        recordingOutcome?.complete(VideoCaptureResult.Error(controllerReleased()))
    }
}

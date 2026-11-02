package com.kashif.cameraK.controller

import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoCaptureResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import platform.AVFoundation.AVCaptureFileOutput
import platform.AVFoundation.AVCaptureFileOutputRecordingDelegateProtocol
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject

/**
 * Delegate for AVCaptureMovieFileOutput recording callbacks.
 */
@OptIn(ExperimentalForeignApi::class)
class VideoRecordingDelegate :
    NSObject(),
    AVCaptureFileOutputRecordingDelegateProtocol {

    var onFinished: ((VideoCaptureResult) -> Unit)? = null

    /** Runs with the result when the recording ends for any reason, before [onFinished]. */
    internal var onRecordingEnded: ((VideoCaptureResult) -> Unit)? = null

    /** Completes when AVFoundation reports the recording started, or fails if it ended first. */
    internal val started = CompletableDeferred<Unit>()

    /** Completes with the recording's result when AVFoundation has finished the file. */
    internal val finished = CompletableDeferred<VideoCaptureResult>()
    private var startTimeMs: Long = 0L

    override fun captureOutput(
        captureOutput: AVCaptureFileOutput,
        didStartRecordingToOutputFileAtURL: NSURL,
        fromConnections: List<*>,
    ) {
        startTimeMs = (NSDate().timeIntervalSince1970 * 1000).toLong()
        started.complete(Unit)
    }

    override fun captureOutput(
        captureOutput: AVCaptureFileOutput,
        didFinishRecordingToOutputFileAtURL: NSURL,
        fromConnections: List<*>,
        error: NSError?,
    ) {
        val filePath = didFinishRecordingToOutputFileAtURL.path ?: ""
        val durationMs = (NSDate().timeIntervalSince1970 * 1000).toLong() - startTimeMs

        val result = if (error != null) {
            // Check if recording was stopped by user (not a real error)
            // AVFoundation sets error when recording is stopped normally in some cases
            val fileExists = platform.Foundation.NSFileManager.defaultManager.fileExistsAtPath(filePath)
            if (fileExists && durationMs > 0) {
                VideoCaptureResult.Success(filePath, durationMs)
            } else {
                VideoCaptureResult.Error(Exception(error.localizedDescription))
            }
        } else {
            VideoCaptureResult.Success(filePath, durationMs)
        }
        if (!started.isCompleted) {
            started.completeExceptionally(IllegalStateException("The recording ended before it started"))
        }
        finished.complete(result)
        onRecordingEnded?.invoke(result)
        try {
            onFinished?.invoke(result)
        } catch (e: Exception) {
            CameraKLogger.e("CameraK", "CameraK: a recording-finished callback failed", e)
        }
    }
}

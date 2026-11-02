package com.kashif.cameraK.controller

import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoCaptureResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import platform.AVFoundation.AVCaptureFileOutput
import platform.AVFoundation.AVCaptureFileOutputRecordingDelegateProtocol
import platform.AVFoundation.AVErrorRecordingSuccessfullyFinishedKey
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject

/**
 * Delegate for AVCaptureMovieFileOutput recording callbacks.
 */
private fun NSError.recordingFinishedSuccessfully(): Boolean =
    when (val finished = userInfo[AVErrorRecordingSuccessfullyFinishedKey]) {
        is NSNumber -> finished.boolValue
        is Boolean -> finished
        else -> false
    }

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

        // AVFoundation reports an error for recordings that still produced a playable file (a duration
        // or size limit, some interruptions) and says so in AVErrorRecordingSuccessfullyFinishedKey.
        // Anything else (disk full, a failure mid-recording) is an error; the partial file is kept.
        val result = if (error == null || error.recordingFinishedSuccessfully()) {
            VideoCaptureResult.Success(filePath, durationMs)
        } else {
            CameraKLogger.e("CameraK", "CameraK: the recording failed: ${error.domain} ${error.code}")
            VideoCaptureResult.Error(Exception("Recording failed: ${error.summary}", NSErrorException(error)))
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

package com.kashif.cameraK.controller

import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoCaptureResult
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureFileOutput
import platform.AVFoundation.AVCaptureFileOutputRecordingDelegateProtocol
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject

/**
 * Delegate for AVCaptureMovieFileOutput recording callbacks. The engine's recorder hears AVFoundation's
 * start and finish through [onPlatformStarted] and [onPlatformFinished], on AVFoundation's queue.
 */
@OptIn(ExperimentalForeignApi::class)
class VideoRecordingDelegate :
    NSObject(),
    AVCaptureFileOutputRecordingDelegateProtocol {

    var onFinished: ((VideoCaptureResult) -> Unit)? = null

    /** AVFoundation's didStartRecording. */
    internal var onPlatformStarted: (() -> Unit)? = null

    /** AVFoundation's didFinishRecording, with its error (null for none). */
    internal var onPlatformFinished: ((error: NSError?) -> Unit)? = null

    private var startTimeMs: Long = 0L

    override fun captureOutput(
        captureOutput: AVCaptureFileOutput,
        didStartRecordingToOutputFileAtURL: NSURL,
        fromConnections: List<*>,
    ) {
        startTimeMs = (NSDate().timeIntervalSince1970 * 1000).toLong()
        onPlatformStarted?.invoke()
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
        val result = if (error == null || error.recordedPlayably) {
            VideoCaptureResult.Success(filePath, durationMs)
        } else {
            val failure = Exception("Recording failed: ${error.summary}", NSErrorException(error))
            CameraKLogger.e("CameraK", "CameraK: the recording failed", failure)
            VideoCaptureResult.Error(failure)
        }
        onPlatformFinished?.invoke(error)
        try {
            onFinished?.invoke(result)
        } catch (e: Exception) {
            CameraKLogger.e("CameraK", "CameraK: a recording-finished callback failed", e)
        }
    }
}

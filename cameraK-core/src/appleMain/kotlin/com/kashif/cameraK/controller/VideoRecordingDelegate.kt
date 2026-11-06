package com.kashif.cameraK.controller

import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureFileOutput
import platform.AVFoundation.AVCaptureFileOutputRecordingDelegateProtocol
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.darwin.NSObject

/**
 * Delegate for AVCaptureMovieFileOutput recording callbacks. The engine's recorder hears AVFoundation's
 * start and finish through [onPlatformStarted] and [onPlatformFinished], on AVFoundation's queue, and
 * turns them into its own outcome. A recording that failed without a playable file is logged here,
 * with AVFoundation's error.
 */
@OptIn(ExperimentalForeignApi::class)
internal class VideoRecordingDelegate :
    NSObject(),
    AVCaptureFileOutputRecordingDelegateProtocol {

    /** AVFoundation's didStartRecording. */
    var onPlatformStarted: (() -> Unit)? = null

    /** AVFoundation's didFinishRecording, with its error (null for none). */
    var onPlatformFinished: ((error: NSError?) -> Unit)? = null

    override fun captureOutput(
        captureOutput: AVCaptureFileOutput,
        didStartRecordingToOutputFileAtURL: NSURL,
        fromConnections: List<*>,
    ) {
        onPlatformStarted?.invoke()
    }

    override fun captureOutput(
        captureOutput: AVCaptureFileOutput,
        didFinishRecordingToOutputFileAtURL: NSURL,
        fromConnections: List<*>,
        error: NSError?,
    ) {
        // AVFoundation reports an error for recordings that still produced a playable file (a duration
        // or size limit, some interruptions) and says so in AVErrorRecordingSuccessfullyFinishedKey.
        if (error != null && !error.recordedPlayably) {
            val failure = Exception("Recording failed: ${error.summary}", NSErrorException(error))
            CameraKLogger.e("CameraK", "CameraK: the recording failed", failure)
        }
        onPlatformFinished?.invoke(error)
    }
}

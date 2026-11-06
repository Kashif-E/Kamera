package com.kashif.cameraK.controller

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
 * Delegate for AVCaptureMovieFileOutput recording callbacks.
 */
@OptIn(ExperimentalForeignApi::class)
class VideoRecordingDelegate :
    NSObject(),
    AVCaptureFileOutputRecordingDelegateProtocol {

    var onFinished: ((VideoCaptureResult) -> Unit)? = null

    // Called for every finished recording, including ones that end without stopRecording().
    internal var onRecordingEnded: (() -> Unit)? = null

    // Set per recording, so a late finish callback can't pick up the next recording's setting.
    internal var savesToCustomDirectory = false
    private var startTimeMs: Long = 0L

    override fun captureOutput(
        captureOutput: AVCaptureFileOutput,
        didStartRecordingToOutputFileAtURL: NSURL,
        fromConnections: List<*>,
    ) {
        startTimeMs = (NSDate().timeIntervalSince1970 * 1000).toLong()
    }

    override fun captureOutput(
        captureOutput: AVCaptureFileOutput,
        didFinishRecordingToOutputFileAtURL: NSURL,
        fromConnections: List<*>,
        error: NSError?,
    ) {
        onRecordingEnded?.invoke()
        val filePath = didFinishRecordingToOutputFileAtURL.path ?: ""
        val durationMs = (NSDate().timeIntervalSince1970 * 1000).toLong() - startTimeMs

        if (error != null) {
            // Check if recording was stopped by user (not a real error)
            // AVFoundation sets error when recording is stopped normally in some cases
            val fileExists = platform.Foundation.NSFileManager.defaultManager.fileExistsAtPath(filePath)
            if (fileExists && durationMs > 0) {
                onFinished?.invoke(VideoCaptureResult.Success(filePath, durationMs))
            } else {
                onFinished?.invoke(VideoCaptureResult.Error(Exception(error.localizedDescription)))
            }
        } else {
            onFinished?.invoke(VideoCaptureResult.Success(filePath, durationMs))
        }
    }
}

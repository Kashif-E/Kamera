package com.kashif.cameraK.controller

import platform.AVFoundation.AVCaptureFileOutputRecordingDelegateProtocol
import platform.AVFoundation.AVCaptureMovieFileOutput
import platform.AVFoundation.AVCaptureVideoOrientation
import platform.AVFoundation.AVMediaTypeVideo
import platform.Foundation.NSURL

/**
 * The movie-file-output calls a recording makes. Production calls AVFoundation; tests substitute one
 * that records each call and drive the recording delegate's callbacks themselves, since the
 * simulator has no camera to record from.
 */
internal interface MovieRecorder {
    fun isRecording(output: AVCaptureMovieFileOutput): Boolean

    /** Starts recording to [url] with the video connection set to [orientation]. */
    fun start(
        output: AVCaptureMovieFileOutput,
        url: NSURL,
        orientation: AVCaptureVideoOrientation,
        delegate: AVCaptureFileOutputRecordingDelegateProtocol,
    )

    fun stop(output: AVCaptureMovieFileOutput)

    /** iOS 18+ only: AVCaptureFileOutput's pause and resume do not exist below it. */
    fun pause(output: AVCaptureMovieFileOutput)

    /** iOS 18+ only. */
    fun resume(output: AVCaptureMovieFileOutput)

    /** iOS 18+ only. */
    fun isPaused(output: AVCaptureMovieFileOutput): Boolean
}

internal object AVMovieRecorder : MovieRecorder {
    override fun isRecording(output: AVCaptureMovieFileOutput): Boolean = output.isRecording()

    override fun start(
        output: AVCaptureMovieFileOutput,
        url: NSURL,
        orientation: AVCaptureVideoOrientation,
        delegate: AVCaptureFileOutputRecordingDelegateProtocol,
    ) {
        output.connectionWithMediaType(AVMediaTypeVideo)?.let { connection ->
            if (connection.isVideoOrientationSupported()) connection.videoOrientation = orientation
        }
        output.startRecordingToOutputFileURL(url, recordingDelegate = delegate)
    }

    override fun stop(output: AVCaptureMovieFileOutput) = output.stopRecording()

    override fun pause(output: AVCaptureMovieFileOutput) = output.pauseRecording()

    override fun resume(output: AVCaptureMovieFileOutput) = output.resumeRecording()

    override fun isPaused(output: AVCaptureMovieFileOutput): Boolean = output.isRecordingPaused()
}

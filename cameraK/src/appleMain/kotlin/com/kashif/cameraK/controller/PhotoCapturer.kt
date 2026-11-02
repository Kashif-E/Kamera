package com.kashif.cameraK.controller

import platform.AVFoundation.AVCapturePhoto
import platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVCapturePhotoSettings
import platform.AVFoundation.AVCaptureResolvedPhotoSettings
import platform.AVFoundation.fileDataRepresentation
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.darwin.NSObject

/**
 * Issues a capture on the photo output. Production calls capturePhotoWithSettings; tests substitute
 * one that records the request, since the simulator's photo output has no camera to capture from.
 */
internal fun interface PhotoCapturer {
    fun capture(
        output: AVCapturePhotoOutput,
        settings: AVCapturePhotoSettings,
        delegate: AVCapturePhotoCaptureDelegateProtocol,
    )
}

internal val AVPhotoCapturer = PhotoCapturer { output, settings, delegate ->
    output.capturePhotoWithSettings(settings, delegate = delegate)
}

/**
 * What a photo capture delegate does with AVFoundation's callbacks, reachable without an
 * AVCapturePhoto (which only AVFoundation can make), so tests can drive a capture's outcome.
 */
internal interface PhotoCaptureCallbacks {
    /** The photo's encoded data, or the error that prevented it (didFinishProcessingPhoto). */
    fun photoProcessed(data: NSData?, error: NSError?)

    /** AVFoundation's last callback for the capture (didFinishCaptureForResolvedSettings). */
    fun captureFinished(error: NSError?)
}

/**
 * The AVFoundation delegate for one capture; it hands AVFoundation's callbacks to [callbacks]. An
 * Objective-C subclass cannot implement a Kotlin interface, so tests reach [callbacks] directly.
 */
internal class PhotoCaptureDelegate(internal val callbacks: PhotoCaptureCallbacks) :
    NSObject(),
    AVCapturePhotoCaptureDelegateProtocol {
    override fun captureOutput(
        output: AVCapturePhotoOutput,
        didFinishProcessingPhoto: AVCapturePhoto,
        error: NSError?,
    ) {
        callbacks.photoProcessed(if (error == null) didFinishProcessingPhoto.fileDataRepresentation() else null, error)
    }

    override fun captureOutput(
        output: AVCapturePhotoOutput,
        didFinishCaptureForResolvedSettings: AVCaptureResolvedPhotoSettings,
        error: NSError?,
    ) {
        callbacks.captureFinished(error)
    }
}

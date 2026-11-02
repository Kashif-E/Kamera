package com.kashif.cameraK.controller

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVCapturePhoto
import platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVCapturePhotoSettings
import platform.AVFoundation.AVCaptureResolvedPhotoSettings
import platform.AVFoundation.AVCaptureVideoOrientation
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.fileDataRepresentation
import platform.CoreMedia.CMVideoDimensions
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.darwin.NSObject

/** A photo size, as AVFoundation's CMVideoDimensions gives it. */
internal data class PhotoDimensions(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
}

/**
 * The photo-output calls that need a camera behind the output. Production calls AVFoundation; tests
 * substitute one that records each call, since the simulator's photo output has no camera.
 */
internal interface PhotoCapturer {
    /** Captures with [settings], the photo connection set to [orientation] and, when supported, [mirrored]. */
    fun capture(
        output: AVCapturePhotoOutput,
        settings: AVCapturePhotoSettings,
        orientation: AVCaptureVideoOrientation,
        mirrored: Boolean,
        delegate: AVCapturePhotoCaptureDelegateProtocol,
    )

    /** Sets the largest photo the output may deliver; iOS 16+ only, and only a size the camera supports. */
    fun setMaxPhotoDimensions(output: AVCapturePhotoOutput, dimensions: PhotoDimensions)

    /** Has the output allocate what captures with [settings] need ahead of the first shutter press. */
    fun prepare(
        output: AVCapturePhotoOutput,
        settings: List<AVCapturePhotoSettings>,
        completion: (Boolean, NSError?) -> Unit,
    )
}

@OptIn(ExperimentalForeignApi::class)
internal object AVPhotoCapturer : PhotoCapturer {
    override fun capture(
        output: AVCapturePhotoOutput,
        settings: AVCapturePhotoSettings,
        orientation: AVCaptureVideoOrientation,
        mirrored: Boolean,
        delegate: AVCapturePhotoCaptureDelegateProtocol,
    ) {
        output.connectionWithMediaType(AVMediaTypeVideo)?.let { connection ->
            if (connection.isVideoOrientationSupported()) connection.videoOrientation = orientation
            if (connection.isVideoMirroringSupported()) {
                connection.automaticallyAdjustsVideoMirroring = false
                connection.videoMirrored = mirrored
            }
        }
        output.capturePhotoWithSettings(settings, delegate = delegate)
    }

    override fun setMaxPhotoDimensions(output: AVCapturePhotoOutput, dimensions: PhotoDimensions) {
        output.maxPhotoDimensions = dimensions.toCMVideoDimensions()
    }

    override fun prepare(
        output: AVCapturePhotoOutput,
        settings: List<AVCapturePhotoSettings>,
        completion: (Boolean, NSError?) -> Unit,
    ) {
        output.setPreparedPhotoSettingsArray(settings, completionHandler = completion)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun PhotoDimensions.toCMVideoDimensions(): CValue<CMVideoDimensions> = cValue {
    width = this@toCMVideoDimensions.width
    height = this@toCMVideoDimensions.height
}

@OptIn(ExperimentalForeignApi::class)
internal fun CValue<CMVideoDimensions>.toPhotoDimensions(): PhotoDimensions = useContents {
    PhotoDimensions(width, height)
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
